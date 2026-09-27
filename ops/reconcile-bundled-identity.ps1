Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Set-JsonProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        $Value
    )
    $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
}

function Invoke-KeycloakAdminRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$AccessToken,
        $Body
    )
    $request = @{
        Method = $Method
        Uri = $Uri
        Headers = @{ Authorization = "Bearer $AccessToken" }
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $request['ContentType'] = 'application/json'
        $request['Body'] = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    return Invoke-RestMethod @request
}

function Get-KeycloakRealmRole {
    param(
        [Parameter(Mandatory = $true)][string]$AdminBase,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][string]$RoleName,
        [Parameter(Mandatory = $true)][string]$AccessToken
    )
    $roleUri = "$AdminBase/admin/realms/$Realm/roles/$([Uri]::EscapeDataString($RoleName))"
    try {
        return Invoke-KeycloakAdminRequest -Method Get -Uri $roleUri -AccessToken $AccessToken
    }
    catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
        [void](Invoke-KeycloakAdminRequest -Method Post -Uri "$AdminBase/admin/realms/$Realm/roles" `
            -AccessToken $AccessToken -Body @{ name = $RoleName; description = 'Smart Charging platform-wide administrator authority' })
        return Invoke-KeycloakAdminRequest -Method Get -Uri $roleUri -AccessToken $AccessToken
    }
}

function Grant-KeycloakRealmRole {
    param(
        [Parameter(Mandatory = $true)][string]$AdminBase,
        [Parameter(Mandatory = $true)][string]$Realm,
        [Parameter(Mandatory = $true)][string]$UserId,
        [Parameter(Mandatory = $true)]$Role,
        [Parameter(Mandatory = $true)][string]$AccessToken
    )
    [void](Invoke-KeycloakAdminRequest -Method Post `
        -Uri "$AdminBase/admin/realms/$Realm/users/$UserId/role-mappings/realm" `
        -AccessToken $AccessToken -Body @($Role))
}

function Sync-BundledIdentity {
    param([Parameter(Mandatory = $true)][hashtable]$Configuration)

    if ([string]$Configuration['IDENTITY_PROVIDER_MODE'] -ne 'bundled') { return }
    $base = "http://127.0.0.1:$([string]$Configuration['KEYCLOAK_PORT'])"
    $realm = [string]$Configuration['KEYCLOAK_REALM']
    try {
        $serviceToken = Invoke-RestMethod -Method Post `
            -Uri "$base/realms/$realm/protocol/openid-connect/token" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{
                grant_type = 'client_credentials'
                client_id = [string]$Configuration['KEYCLOAK_PROVISIONING_CLIENT_ID']
                client_secret = [string]$Configuration['KEYCLOAK_PROVISIONING_CLIENT_SECRET']
            } -TimeoutSec 20
        $serviceAccessToken = [string]$serviceToken.access_token
        $probeUsername = [Uri]::EscapeDataString([string]$Configuration['PLATFORM_ADMIN_USERNAME'])
        $probeUsers = @(Invoke-KeycloakAdminRequest -Method Get `
            -Uri "$base/admin/realms/$realm/users?username=$probeUsername&exact=true&max=2" `
            -AccessToken $serviceAccessToken)
        $probeRole = Invoke-KeycloakAdminRequest -Method Get `
            -Uri "$base/admin/realms/$realm/roles/platform_admin" -AccessToken $serviceAccessToken
        $probeRealm = Invoke-KeycloakAdminRequest -Method Get `
            -Uri "$base/admin/realms/$realm" -AccessToken $serviceAccessToken
        if ($probeUsers.Count -eq 1) {
            $probeMappings = @(Invoke-KeycloakAdminRequest -Method Get `
                -Uri "$base/admin/realms/$realm/users/$([string]$probeUsers[0].id)/role-mappings/realm" `
                -AccessToken $serviceAccessToken)
            $probeCredentials = @(Invoke-KeycloakAdminRequest -Method Get `
                -Uri "$base/admin/realms/$realm/users/$([string]$probeUsers[0].id)/credentials" `
                -AccessToken $serviceAccessToken)
            $probeActionsProperty = $probeUsers[0].PSObject.Properties['requiredActions']
            $probeActions = if ($null -eq $probeActionsProperty) { @() } else { @($probeActionsProperty.Value) }
            $mfaReady = ($probeCredentials.type -contains 'otp') -or ($probeActions -contains 'CONFIGURE_TOTP')
            $probeEmailProperty = $probeUsers[0].PSObject.Properties['email']
            $probeEmail = if ($null -eq $probeEmailProperty) { '' } else { [string]$probeEmailProperty.Value }
            $configuredPlatformEmail = [string]$Configuration['PLATFORM_ADMIN_EMAIL']
            $platformEmailReady = [string]::IsNullOrWhiteSpace($configuredPlatformEmail) -or
                $probeEmail -eq $configuredPlatformEmail
            $probeSmtpProperty = $probeRealm.PSObject.Properties['smtpServer']
            $probeSmtp = if ($null -eq $probeSmtpProperty) { $null } else { $probeSmtpProperty.Value }
            $emailReady = [string]$Configuration['IDENTITY_EMAIL_ENABLED'] -ne 'true' -or
                ($null -ne $probeSmtp -and
                 [string]$probeSmtp.host -eq [string]$Configuration['IDENTITY_SMTP_HOST'] -and
                 [string]$probeSmtp.from -eq [string]$Configuration['IDENTITY_SMTP_FROM'])
            $realmReady = $probeRealm.eventsEnabled -eq $true -and
                $probeRealm.registrationAllowed -ne $true -and
                $probeRealm.resetPasswordAllowed -eq ([string]$Configuration['IDENTITY_EMAIL_ENABLED'] -eq 'true') -and
                -not [string]::IsNullOrWhiteSpace([string]$probeRealm.passwordPolicy)
            if ($null -ne $probeRole -and $probeMappings.name -contains 'platform_admin' -and
                    $mfaReady -and $platformEmailReady -and $emailReady -and $realmReady) {
                Write-Host 'Bundled identity account lifecycle settings are already active.' -ForegroundColor DarkGray
                return
            }
        }
    }
    catch {
        Write-Host 'Bundled identity requires a one-time management permission reconciliation.' -ForegroundColor DarkGray
    }
    $token = Invoke-RestMethod -Method Post `
        -Uri "$base/realms/master/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            grant_type = 'password'
            client_id = 'admin-cli'
            username = [string]$Configuration['KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME']
            password = [string]$Configuration['KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD']
        } -TimeoutSec 20
    if ([string]::IsNullOrWhiteSpace([string]$token.access_token)) {
        throw 'Bundled identity reconciliation could not obtain an administration token.'
    }
    $accessToken = [string]$token.access_token

    $realmRepresentation = Invoke-KeycloakAdminRequest -Method Get -Uri "$base/admin/realms/$realm" -AccessToken $accessToken
    Set-JsonProperty -Object $realmRepresentation -Name registrationAllowed -Value $false
    Set-JsonProperty -Object $realmRepresentation -Name resetPasswordAllowed `
        -Value ([string]$Configuration['IDENTITY_EMAIL_ENABLED'] -eq 'true')
    Set-JsonProperty -Object $realmRepresentation -Name bruteForceProtected -Value $true
    Set-JsonProperty -Object $realmRepresentation -Name passwordPolicy `
        -Value 'length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1) and passwordHistory(5)'
    Set-JsonProperty -Object $realmRepresentation -Name eventsEnabled -Value $true
    Set-JsonProperty -Object $realmRepresentation -Name eventsExpiration -Value 7776000
    Set-JsonProperty -Object $realmRepresentation -Name enabledEventTypes `
        -Value @('LOGIN', 'LOGIN_ERROR', 'UPDATE_PASSWORD', 'UPDATE_TOTP', 'REMOVE_TOTP')
    Set-JsonProperty -Object $realmRepresentation -Name adminEventsEnabled -Value $true
    Set-JsonProperty -Object $realmRepresentation -Name adminEventsDetailsEnabled -Value $false
    if ([string]$Configuration['IDENTITY_EMAIL_ENABLED'] -eq 'true') {
        Set-JsonProperty -Object $realmRepresentation -Name smtpServer -Value @{
            host = [string]$Configuration['IDENTITY_SMTP_HOST']
            port = [string]$Configuration['IDENTITY_SMTP_PORT']
            from = [string]$Configuration['IDENTITY_SMTP_FROM']
            fromDisplayName = [string]$Configuration['IDENTITY_SMTP_FROM_DISPLAY_NAME']
            auth = 'true'
            user = [string]$Configuration['IDENTITY_SMTP_USERNAME']
            password = [string]$Configuration['IDENTITY_SMTP_PASSWORD']
            starttls = [string]$Configuration['IDENTITY_SMTP_STARTTLS']
            ssl = 'false'
        }
    }
    else {
        Set-JsonProperty -Object $realmRepresentation -Name smtpServer -Value @{}
    }
    [void](Invoke-KeycloakAdminRequest -Method Put -Uri "$base/admin/realms/$realm" `
        -AccessToken $accessToken -Body $realmRepresentation)

    $platformRole = Get-KeycloakRealmRole -AdminBase $base -Realm $realm -RoleName 'platform_admin' -AccessToken $accessToken
    $adminUsername = [Uri]::EscapeDataString([string]$Configuration['PLATFORM_ADMIN_USERNAME'])
    $adminUsers = @(Invoke-KeycloakAdminRequest -Method Get `
        -Uri "$base/admin/realms/$realm/users?username=$adminUsername&exact=true&max=2" -AccessToken $accessToken)
    if ($adminUsers.Count -ne 1) {
        throw 'The configured platform administrator was not found uniquely in the bundled identity realm.'
    }
    $platformUser = $adminUsers[0]
    Grant-KeycloakRealmRole -AdminBase $base -Realm $realm -UserId ([string]$platformUser.id) `
        -Role $platformRole -AccessToken $accessToken
    $credentials = @(Invoke-KeycloakAdminRequest -Method Get `
        -Uri "$base/admin/realms/$realm/users/$([string]$platformUser.id)/credentials" -AccessToken $accessToken)
    $platformUserChanged = $false
    $actionsProperty = $platformUser.PSObject.Properties['requiredActions']
    $actions = if ($null -eq $actionsProperty) { @() } else { @($actionsProperty.Value) }
    if (-not ($credentials | Where-Object { $_.type -eq 'otp' })) {
        if ($actions -notcontains 'CONFIGURE_TOTP') { $actions += 'CONFIGURE_TOTP' }
        $platformUserChanged = $true
    }
    $configuredPlatformEmail = [string]$Configuration['PLATFORM_ADMIN_EMAIL']
    $userEmailProperty = $platformUser.PSObject.Properties['email']
    $userEmail = if ($null -eq $userEmailProperty) { '' } else { [string]$userEmailProperty.Value }
    if (-not [string]::IsNullOrWhiteSpace($configuredPlatformEmail) -and
            $userEmail -ne $configuredPlatformEmail) {
        Set-JsonProperty -Object $platformUser -Name email -Value $configuredPlatformEmail
        Set-JsonProperty -Object $platformUser -Name emailVerified -Value $false
        if ([string]$Configuration['IDENTITY_EMAIL_ENABLED'] -eq 'true' -and
                $actions -notcontains 'VERIFY_EMAIL') {
            $actions += 'VERIFY_EMAIL'
        }
        $platformUserChanged = $true
    }
    if ($platformUserChanged) {
        Set-JsonProperty -Object $platformUser -Name requiredActions -Value $actions
        [void](Invoke-KeycloakAdminRequest -Method Put `
            -Uri "$base/admin/realms/$realm/users/$([string]$platformUser.id)" `
            -AccessToken $accessToken -Body $platformUser)
    }

    $provisioningClientId = [Uri]::EscapeDataString([string]$Configuration['KEYCLOAK_PROVISIONING_CLIENT_ID'])
    $provisioningClients = @(Invoke-KeycloakAdminRequest -Method Get `
        -Uri "$base/admin/realms/$realm/clients?clientId=$provisioningClientId" -AccessToken $accessToken)
    $managementClients = @(Invoke-KeycloakAdminRequest -Method Get `
        -Uri "$base/admin/realms/$realm/clients?clientId=realm-management" -AccessToken $accessToken)
    if ($provisioningClients.Count -ne 1 -or $managementClients.Count -ne 1) {
        throw 'Bundled identity management clients were not found uniquely.'
    }
    $provisioningInternalId = [string]$provisioningClients[0].id
    $managementInternalId = [string]$managementClients[0].id
    $serviceUser = Invoke-KeycloakAdminRequest -Method Get `
        -Uri "$base/admin/realms/$realm/clients/$provisioningInternalId/service-account-user" -AccessToken $accessToken
    $managementRoles = @()
    foreach ($roleName in @('manage-users', 'view-users', 'query-users', 'view-events', 'view-realm')) {
        $managementRoles += Invoke-KeycloakAdminRequest -Method Get `
            -Uri "$base/admin/realms/$realm/clients/$managementInternalId/roles/$roleName" -AccessToken $accessToken
    }
    [void](Invoke-KeycloakAdminRequest -Method Post `
        -Uri "$base/admin/realms/$realm/users/$([string]$serviceUser.id)/role-mappings/clients/$managementInternalId" `
        -AccessToken $accessToken -Body $managementRoles)

    Write-Host 'Bundled identity account lifecycle, MFA and audit settings are reconciled.' -ForegroundColor DarkGray
}

param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{1,62}$')][string]$TenantCode,
    [Parameter(Mandatory)][ValidateLength(1, 160)][string]$TenantDisplayName,
    [ValidateLength(1, 160)][string]$AdminSubject,
    [ValidateLength(1, 120)][string]$AdminDisplayName,
    [string]$ApiBase = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')

function Get-ProvisioningFailureDetail {
    param([Parameter(Mandatory)][System.Management.Automation.ErrorRecord]$Failure)

    if (-not [string]::IsNullOrWhiteSpace([string]$Failure.ErrorDetails.Message)) {
        return [string]$Failure.ErrorDetails.Message
    }
    $response = $Failure.Exception.Response
    if ($null -ne $response) {
        try {
            if ($null -ne $response.Content) {
                return [string]$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
            $stream = $response.GetResponseStream()
            if ($null -ne $stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                try { return $reader.ReadToEnd() }
                finally { $reader.Dispose() }
            }
        }
        catch {
            return [string]$Failure.Exception.Message
        }
    }
    return [string]$Failure.Exception.Message
}

function Resolve-BundledPlatformAdminSubject {
    param([Parameter(Mandatory)][hashtable]$Configuration)

    $issuer = ([string]$Configuration['OIDC_ISSUER_URI']).TrimEnd('/')
    $realmMarker = '/realms/'
    $realmPosition = $issuer.IndexOf($realmMarker, [StringComparison]::OrdinalIgnoreCase)
    if ($realmPosition -le 0) {
        throw 'Bundled OIDC issuer URI does not contain a Keycloak realm path.'
    }
    $keycloakBase = $issuer.Substring(0, $realmPosition)
    $realm = [string]$Configuration['KEYCLOAK_REALM']
    $username = [string]$Configuration['PLATFORM_ADMIN_USERNAME']
    $bootstrapUsername = [string]$Configuration['KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME']
    $bootstrapPassword = [string]$Configuration['KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD']
    foreach ($requiredValue in @($realm, $username, $bootstrapUsername, $bootstrapPassword)) {
        if ([string]::IsNullOrWhiteSpace($requiredValue)) {
            throw 'Bundled identity administration configuration is incomplete.'
        }
    }

    try {
        $adminTokenResponse = Invoke-RestMethod -Method Post `
            -Uri "$keycloakBase/realms/master/protocol/openid-connect/token" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{
                grant_type = 'password'
                client_id = 'admin-cli'
                username = $bootstrapUsername
                password = $bootstrapPassword
            } `
            -TimeoutSec 15
        $adminToken = [string]$adminTokenResponse.access_token
        if ([string]::IsNullOrWhiteSpace($adminToken)) {
            throw 'Keycloak did not issue an administration token.'
        }
        $encodedRealm = [Uri]::EscapeDataString($realm)
        $encodedUsername = [Uri]::EscapeDataString($username)
        $users = @(Invoke-RestMethod -Method Get `
            -Uri "$keycloakBase/admin/realms/$encodedRealm/users?username=$encodedUsername&exact=true" `
            -Headers @{ Authorization = "Bearer $adminToken" } `
            -TimeoutSec 15)
        $matches = @($users | Where-Object {
            [string]::Equals([string]$_.username, $username, [StringComparison]::Ordinal)
        })
        if ($matches.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$matches[0].id)) {
            throw "Expected exactly one bundled platform administrator named '$username'."
        }
        return [string]$matches[0].id
    }
    catch {
        $detail = Get-ProvisioningFailureDetail -Failure $_
        throw "Cannot resolve the real Keycloak platform administrator subject: $detail"
    }
}

$configurationPath = Get-DeploymentConfigurationPath -Workspace $workspace
$configuration = if (Test-Path -LiteralPath $configurationPath -PathType Leaf) {
    Read-DeploymentConfiguration -Path $configurationPath
}
else {
    @{}
}
$bundledIdentity = $configuration.ContainsKey('IDENTITY_PROVIDER_MODE') -and
    [string]$configuration['IDENTITY_PROVIDER_MODE'] -eq 'bundled'
$accessToken = $env:INTERNAL_PROVISIONING_TOKEN
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    if (-not $bundledIdentity) {
        throw 'Set INTERNAL_PROVISIONING_TOKEN to a real OIDC access token with SCOPE_internal.'
    }
    $tokenRequest = @{
        Method = 'Post'
        Uri = [string]$configuration['VITE_OIDC_TOKEN_ENDPOINT']
        ContentType = 'application/x-www-form-urlencoded'
        Body = @{
            grant_type = 'client_credentials'
            client_id = [string]$configuration['KEYCLOAK_PROVISIONING_CLIENT_ID']
            client_secret = [string]$configuration['KEYCLOAK_PROVISIONING_CLIENT_SECRET']
        }
        TimeoutSec = 15
    }
    $tokenResponse = Invoke-RestMethod @tokenRequest
    $accessToken = [string]$tokenResponse.access_token
}

if ([string]::IsNullOrWhiteSpace($AdminSubject) -and $bundledIdentity) {
    $AdminSubject = Resolve-BundledPlatformAdminSubject -Configuration $configuration
}
if ([string]::IsNullOrWhiteSpace($AdminDisplayName) -and $bundledIdentity) {
    $AdminDisplayName = [string]$configuration['PLATFORM_ADMIN_DISPLAY_NAME']
}
if ([string]::IsNullOrWhiteSpace($AdminSubject) -or [string]::IsNullOrWhiteSpace($AdminDisplayName)) {
    throw 'AdminSubject and AdminDisplayName are required when an external identity provider is used.'
}

$body = @{
    code = $TenantCode
    displayName = $TenantDisplayName
    adminSubject = $AdminSubject
    adminDisplayName = $AdminDisplayName
}
if ($bundledIdentity) {
    $body['tenantId'] = [string]$configuration['INITIAL_TENANT_ID']
}
$body = $body | ConvertTo-Json
$request = @{
    Method = 'Post'
    Uri = "$($ApiBase.TrimEnd('/'))/internal/v1/tenants"
    Headers = @{ Authorization = "Bearer $accessToken" }
    ContentType = 'application/json'
    Body = $body
    TimeoutSec = 15
}

$result = try {
    Invoke-RestMethod @request
}
catch {
    $detail = Get-ProvisioningFailureDetail -Failure $_
    throw "Tenant provisioning failed: $detail"
}
$result | ConvertTo-Json -Depth 4

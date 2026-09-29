param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{1,62}$')][string]$TenantCode,
    [Parameter(Mandatory)][ValidateLength(1, 160)][string]$TenantDisplayName,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$')][string]$AdminUsername,
    [Parameter(Mandatory)][ValidatePattern('^[^@\s]+@[^@\s]+\.[^@\s]+$')][string]$AdminEmail,
    [Parameter(Mandatory)][ValidateLength(1, 120)][string]$AdminDisplayName,
    [string]$PlatformUsername,
    [SecureString]$PlatformPassword,
    [string]$ApiBase = 'http://127.0.0.1:8088/api/v1'
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')

function Get-ProvisioningFailureDetail {
    param([Parameter(Mandatory)][System.Management.Automation.ErrorRecord]$Failure)

    if (-not [string]::IsNullOrWhiteSpace([string]$Failure.ErrorDetails.Message)) {
        return [string]$Failure.ErrorDetails.Message
    }
    return [string]$Failure.Exception.Message
}

$configurationPath = Get-DeploymentConfigurationPath -Workspace $workspace
if (-not (Test-Path -LiteralPath $configurationPath -PathType Leaf)) {
    throw '.env was not found. Run config-manager.cmd init, edit .env, then start the platform.'
}
$configuration = Read-DeploymentConfiguration -Path $configurationPath
if ([string]$configuration['IDENTITY_PROVIDER_MODE'] -ne 'database') {
    throw 'This helper is for built-in database login. With external OIDC, create tenants in the platform console.'
}
if ([string]::IsNullOrWhiteSpace($PlatformUsername)) {
    $PlatformUsername = [string]$configuration['PLATFORM_ADMIN_USERNAME']
}
$plainPlatformPassword = $null
$passwordPointer = [IntPtr]::Zero

try {
    if ($null -eq $PlatformPassword) {
        $PlatformPassword = Read-Host 'Current platform administrator password' -AsSecureString
    }
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($PlatformPassword)
    $plainPlatformPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $session = Invoke-RestMethod -Method Post -Uri "$($ApiBase.TrimEnd('/'))/auth/admin/login" `
        -ContentType 'application/json' -Body (@{
            username = $PlatformUsername
            password = $plainPlatformPassword
        } | ConvertTo-Json) -TimeoutSec 15
    if ($session.mustChangePassword) {
        throw 'The platform password is still temporary. Sign in through the web console and change it first.'
    }
    $result = Invoke-RestMethod -Method Post -Uri "$($ApiBase.TrimEnd('/'))/platform/tenants" `
        -Headers @{ Authorization = "Bearer $([string]$session.accessToken)" } `
        -ContentType 'application/json' -Body (@{
            code = $TenantCode
            displayName = $TenantDisplayName
            adminUsername = $AdminUsername
            adminEmail = $AdminEmail
            adminDisplayName = $AdminDisplayName
        } | ConvertTo-Json) -TimeoutSec 15
    $result | ConvertTo-Json -Depth 4
}
catch {
    $detail = Get-ProvisioningFailureDetail -Failure $_
    throw "Tenant provisioning failed: $detail"
}
finally {
    $plainPlatformPassword = $null
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}

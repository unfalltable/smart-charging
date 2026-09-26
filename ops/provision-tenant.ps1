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
    $AdminSubject = [string]$configuration['PLATFORM_ADMIN_SUBJECT']
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

$result = Invoke-RestMethod @request
$result | ConvertTo-Json -Depth 4

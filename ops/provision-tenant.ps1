param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{1,62}$')][string]$TenantCode,
    [Parameter(Mandatory)][ValidateLength(1, 160)][string]$TenantDisplayName,
    [Parameter(Mandatory)][ValidateLength(1, 160)][string]$AdminSubject,
    [Parameter(Mandatory)][ValidateLength(1, 120)][string]$AdminDisplayName,
    [string]$ApiBase = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
$accessToken = $env:INTERNAL_PROVISIONING_TOKEN
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw 'Set INTERNAL_PROVISIONING_TOKEN to a real OIDC access token with SCOPE_internal.'
}

$body = @{
    code = $TenantCode
    displayName = $TenantDisplayName
    adminSubject = $AdminSubject
    adminDisplayName = $AdminDisplayName
} | ConvertTo-Json
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

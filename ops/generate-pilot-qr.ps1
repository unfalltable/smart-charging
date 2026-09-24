$ErrorActionPreference = 'Stop'

$tenantId = '11111111-1111-1111-1111-111111111111'
$connectorId = '44444444-4444-4444-4444-000000000001'
$secret = $env:QR_SIGNING_SECRET
if ([string]::IsNullOrWhiteSpace($secret)) {
    $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env.docker'
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    }
    if (Test-Path -LiteralPath $envFile -PathType Leaf) {
        $line = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^QR_SIGNING_SECRET=' } | Select-Object -First 1
        if ($line) { $secret = $line.Substring('QR_SIGNING_SECRET='.Length) }
    }
}
if ([string]::IsNullOrWhiteSpace($secret) -or $secret.Length -lt 32) {
    throw 'Set QR_SIGNING_SECRET to at least 32 characters in the environment or an environment file.'
}
$payload = "sc1.$tenantId.$connectorId"
$hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
try {
    $signature = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload)) |
        ForEach-Object { $_.ToString('x2') })
    Write-Output "$payload.$signature"
}
finally {
    $hmac.Dispose()
}

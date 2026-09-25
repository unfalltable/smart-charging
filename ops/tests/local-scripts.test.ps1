$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$startScriptPath = Join-Path $workspace 'ops/start-local.ps1'
$parserTokens = $null
$parserErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $startScriptPath,
    [ref]$parserTokens,
    [ref]$parserErrors
) | Out-Null
if ($parserErrors.Count -gt 0) {
    throw "Startup script contains PowerShell parse errors: $($parserErrors.Message -join '; ')"
}

$startScript = Get-Content -LiteralPath $startScriptPath -Raw
if ($startScript -match '(?im)^\s*\$home\s*=') {
    throw 'The startup script must not overwrite PowerShell automatic variables.'
}
$provisionScriptPath = Join-Path $workspace 'ops/provision-tenant.ps1'
$provisionTokens = $null
$provisionErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $provisionScriptPath,
    [ref]$provisionTokens,
    [ref]$provisionErrors
) | Out-Null
if ($provisionErrors.Count -gt 0) {
    throw "Tenant provisioning script contains PowerShell parse errors: $($provisionErrors.Message -join '; ')"
}
foreach ($requiredName in @(
    'OIDC_ISSUER_URI',
    'VITE_OIDC_AUTHORIZATION_ENDPOINT',
    'VITE_OIDC_TOKEN_ENDPOINT',
    'VITE_OIDC_CLIENT_ID'
)) {
    if ($startScript -notmatch [regex]::Escape($requiredName)) {
        throw "Startup preflight does not require $requiredName."
    }
}
if (($startScript -notmatch 'empty business database') -or
        ($startScript -notmatch 'No tenant, user, station, device, tariff, order or payment data was created')) {
    throw 'Startup output must explicitly confirm that no business records were seeded.'
}
if ($startScript -notmatch 'PILOT_DEVICE_SECRET' -or $startScript -notmatch 'docker-stop.cmd -DeleteData') {
    throw 'Startup must block legacy demo volumes until the operator explicitly removes them.'
}
$stopScript = Get-Content -LiteralPath (Join-Path $workspace 'ops/stop-local.ps1') -Raw
if ($stopScript -notmatch 'Remove-Item -LiteralPath \$environmentFile') {
    throw 'Deleting legacy Docker data must also remove the obsolete local secret file.'
}

$compose = Get-Content -LiteralPath (Join-Path $workspace 'ops/compose.local.yaml') -Raw
foreach ($forbidden in @('simulator:', 'bootstrap:', 'VITE_LOCAL_MODE', 'SPRING_PROFILES_ACTIVE: local',
        'PILE001', '11111111-1111-1111-1111-111111111111')) {
    if ($compose.Contains($forbidden)) { throw "Runtime compose contains forbidden demo configuration: $forbidden" }
}
if ($compose -notmatch 'SPRING_PROFILES_ACTIVE: production') {
    throw 'Docker runtime must use production security behavior.'
}
if ($compose -notmatch 'profiles: \["device"\]' -or $compose -notmatch 'DEVICE_TLS_ENABLED: "true"') {
    throw 'The real device gateway must be opt-in and require TLS.'
}

$forbiddenRuntimeFiles = @(
    'docker-demo.cmd',
    'simulator/device-simulator.mjs',
    'ops/demo-charge.ps1',
    'ops/pilot-12-port.sql',
    'ops/generate-pilot-qr.ps1'
)
foreach ($relativePath in $forbiddenRuntimeFiles) {
    if (Test-Path -LiteralPath (Join-Path $workspace $relativePath)) {
        throw "Demo runtime artifact must not be shipped: $relativePath"
    }
}

$runtimeSource = @(
    (Get-ChildItem -LiteralPath (Join-Path $workspace 'platform-core/src/main') -Recurse -File),
    (Get-ChildItem -LiteralPath (Join-Path $workspace 'apps') -Recurse -File)
) | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }
$runtimeText = $runtimeSource -join [Environment]::NewLine
foreach ($forbidden in @('LOCAL_SIMULATION', 'simulate-success',
        '11111111-1111-1111-1111-111111111111', '55555555-5555-5555-5555-555555555555',
        'api.example.invalid')) {
    if ($runtimeText.Contains($forbidden)) { throw "Runtime source contains forbidden fake value: $forbidden" }
}

Write-Host 'PASS: production runtime has no seeded business data or simulation bypasses.'

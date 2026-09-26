$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$startScriptPath = Join-Path $workspace 'ops/start-local.ps1'
$configurationScriptPath = Join-Path $workspace 'ops/configuration.ps1'
$managerScriptPath = Join-Path $workspace 'ops/manage-config.ps1'
foreach ($scriptPath in @($startScriptPath, $configurationScriptPath, $managerScriptPath)) {
    $parserTokens = $null
    $parserErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $scriptPath,
        [ref]$parserTokens,
        [ref]$parserErrors
    ) | Out-Null
    if ($parserErrors.Count -gt 0) {
        throw "$scriptPath contains PowerShell parse errors: $($parserErrors.Message -join '; ')"
    }
}

$startScript = Get-Content -LiteralPath $startScriptPath -Raw
$configurationScript = Get-Content -LiteralPath $configurationScriptPath -Raw
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
    if ($configurationScript -notmatch [regex]::Escape($requiredName)) {
        throw "Unified configuration does not require $requiredName."
    }
}
if (($startScript -notmatch 'empty business database') -or
        ($startScript -notmatch 'No tenant, user, station, device, tariff, order or payment data was created')) {
    throw 'Startup output must explicitly confirm that no business records were seeded.'
}
if ($configurationScript -notmatch 'PILOT_DEVICE_SECRET') {
    throw 'Unified configuration must reject legacy demo configuration.'
}
$stopScript = Get-Content -LiteralPath (Join-Path $workspace 'ops/stop-local.ps1') -Raw
if ($stopScript -notmatch 'Remove-Item -LiteralPath \$environmentFile') {
    throw 'Deleting legacy Docker data must also remove the obsolete local secret file.'
}
foreach ($cleanupOverride in @('ADMIN_WEB_PORT', 'DEVICE_GATEWAY_PORT', 'NATS_MONITOR_PORT')) {
    if ($stopScript -notmatch 'SetEnvironmentVariable' -or $stopScript -notmatch $cleanupOverride) {
        throw "Legacy cleanup must override malformed Compose value: $cleanupOverride"
    }
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
foreach ($requiredRuntimeSetting in @('WECHAT_PRIMARY_PRIVATE_KEY_PATH', 'VITE_OIDC_SCOPES', 'RATE_LIMIT_DEFAULT_PER_MINUTE')) {
    if ($compose -notmatch $requiredRuntimeSetting) {
        throw "Docker runtime is not connected to unified setting: $requiredRuntimeSetting"
    }
}

$miniappConfiguration = Get-Content -LiteralPath (Join-Path $workspace 'apps/miniapp/src/config.js') -Raw
if ($miniappConfiguration -notmatch "require\('./deployment.config'\)") {
    throw 'Mini-program configuration must be generated from the unified deployment configuration.'
}
$gitIgnore = Get-Content -LiteralPath (Join-Path $workspace '.gitignore') -Raw
$dockerIgnore = Get-Content -LiteralPath (Join-Path $workspace '.dockerignore') -Raw
foreach ($sensitivePath in @('runtime-secrets', 'apps/miniapp/src/deployment.config.js')) {
    if ($gitIgnore -notmatch [regex]::Escape($sensitivePath) -or $dockerIgnore -notmatch [regex]::Escape($sensitivePath)) {
        throw "Sensitive generated path must be excluded from Git and Docker build context: $sensitivePath"
    }
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

. $configurationScriptPath
$testRoot = Join-Path $workspace ("target/configuration-test-$([guid]::NewGuid().ToString('N'))")
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
$allowedTestPrefix = [IO.Path]::GetFullPath((Join-Path $workspace 'target/configuration-test-'))
if (-not $resolvedTestRoot.StartsWith($allowedTestPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create configuration test outside the workspace target directory.'
}
try {
    [void](New-Item -ItemType Directory -Path (Join-Path $testRoot 'apps/miniapp/src') -Force)
    $testState = Initialize-DeploymentConfiguration -Workspace $testRoot
    if (([string]$testState.Values['POSTGRES_PASSWORD']).Length -ne 64) {
        throw 'Generated database password does not have the required entropy.'
    }
    if ([Convert]::FromBase64String([string]$testState.Values['AUTH_JWT_SECRET_BASE64']).Length -ne 32) {
        throw 'Generated JWT signing key is not exactly 256 bits.'
    }
    $initialErrors = @(Test-DeploymentConfiguration -Workspace $testRoot -Values $testState.Values)
    if (-not ($initialErrors -match 'OIDC_ISSUER_URI')) {
        throw 'Fresh configuration must reject missing real OIDC settings.'
    }
    $testState.Values['OIDC_ISSUER_URI'] = 'https://identity.test.invalid'
    $testState.Values['VITE_OIDC_AUTHORIZATION_ENDPOINT'] = 'https://identity.test.invalid/authorize'
    $testState.Values['VITE_OIDC_TOKEN_ENDPOINT'] = 'https://identity.test.invalid/token'
    $testState.Values['VITE_OIDC_CLIENT_ID'] = 'admin-web-test'
    $testState.Values['CUSTOM_EQUALS_VALUE'] = 'first=second=third'
    Write-DeploymentConfiguration -Path $testState.Path -Values $testState.Values
    $roundTrip = Read-DeploymentConfiguration -Path $testState.Path
    if ($roundTrip['CUSTOM_EQUALS_VALUE'] -ne 'first=second=third') {
        throw 'Configuration parser did not preserve equals signs in values.'
    }
    $validationErrors = @(Test-DeploymentConfiguration -Workspace $testRoot -Values $roundTrip)
    if ($validationErrors.Count -ne 0) {
        throw "Valid unified configuration was rejected: $($validationErrors -join '; ')"
    }
    $miniappPath = Export-MiniappDeploymentConfiguration -Workspace $testRoot -Values $roundTrip
    $miniappText = Get-Content -LiteralPath $miniappPath -Raw
    if ($miniappText.Contains([string]$roundTrip['POSTGRES_PASSWORD']) -or $miniappText -notmatch 'module.exports') {
        throw 'Mini-program export is invalid or contains a server-side secret.'
    }
}
finally {
    if (Test-Path -LiteralPath $resolvedTestRoot -PathType Container) {
        [IO.Directory]::Delete($resolvedTestRoot, $true)
    }
}

Write-Host 'PASS: production runtime and unified configuration checks passed.'

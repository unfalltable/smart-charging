$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scripts = @(
    'ops/start-local.ps1',
    'ops/stop-local.ps1',
    'ops/configuration.ps1',
    'ops/manage-config.ps1',
    'ops/upload-miniapp.ps1',
    'ops/http-response.ps1'
)
foreach ($relativePath in $scripts) {
    $scriptPath = Join-Path $workspace $relativePath
    $parserTokens = $null
    $parserErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $scriptPath, [ref]$parserTokens, [ref]$parserErrors) | Out-Null
    if ($parserErrors.Count -gt 0) {
        throw "$relativePath contains PowerShell parse errors: $($parserErrors.Message -join '; ')"
    }
}

$startScript = Get-Content -LiteralPath (Join-Path $workspace 'ops/start-local.ps1') -Raw
$stopScript = Get-Content -LiteralPath (Join-Path $workspace 'ops/stop-local.ps1') -Raw
$compose = Get-Content -LiteralPath (Join-Path $workspace 'ops/compose.local.yaml') -Raw
$adminDockerfile = Get-Content -LiteralPath (Join-Path $workspace 'ops/admin-web.Dockerfile') -Raw

if ($startScript -match '(?im)^\s*\$home\s*=' -or $startScript -match '(?i)keycloak') {
    throw 'The startup script must not overwrite automatic variables or depend on Keycloak.'
}
if ($startScript -notmatch 'up --detach --no-build' -or
        $startScript -notmatch 'without seeding business data' -or
        $startScript -notmatch 'Startup did not create any tenant, station, device, tariff, order or payment data') {
    throw 'Startup must be deterministic and explicitly avoid fake business data.'
}
if ($startScript -notmatch 'logs --no-color --tail 160 core') {
    throw 'Startup must print core diagnostics when a service dependency fails.'
}
if ($stopScript -match 'Remove-Item -LiteralPath \$environmentFile' -or
        $stopScript -notmatch 'root \.env file was preserved') {
    throw 'Deleting Docker data must preserve the operator-managed root .env file.'
}
foreach ($cleanupOverride in @('ADMIN_WEB_PORT', 'DEVICE_GATEWAY_PORT', 'NATS_MONITOR_PORT')) {
    if ($stopScript -notmatch $cleanupOverride) {
        throw "Cleanup must override malformed Compose value: $cleanupOverride"
    }
}

foreach ($forbidden in @('keycloak:', 'bundled-identity', 'postgres-init-keycloak', 'VITE_LOCAL_MODE',
        'SPRING_PROFILES_ACTIVE: local', 'simulator:', 'bootstrap:', 'PILE001')) {
    if ($compose.Contains($forbidden)) { throw "Runtime Compose contains obsolete or demo configuration: $forbidden" }
}
foreach ($required in @('SPRING_PROFILES_ACTIVE: production',
        'PLATFORM_ADMIN_SUBJECT', 'PLATFORM_ADMIN_USERNAME', 'PLATFORM_ADMIN_PASSWORD',
        'IDENTITY_LOGIN_EVENT_RETENTION_DAYS', 'IDENTITY_EXPIRED_TOKEN_RETENTION_DAYS',
        'profiles: ["device"]', 'DEVICE_TLS_ENABLED: "true"', 'APP_BUILD_REVISION')) {
    if (-not $compose.Contains($required)) { throw "Runtime Compose setting is missing: $required" }
}
if ($adminDockerfile -match 'OIDC|VITE_AUTH_MODE') {
    throw 'Admin-web build must use the single built-in username/password flow.'
}

$httpResponseScriptPath = Join-Path $workspace 'ops/http-response.ps1'
. $httpResponseScriptPath
$revisionBytes = [Text.Encoding]::UTF8.GetBytes("revision-check`n")
if ((Convert-HttpContentToText -Content $revisionBytes).Trim() -ne 'revision-check') {
    throw 'HTTP response byte content was not decoded as UTF-8 text.'
}
foreach ($requiredRevisionCheck in @('/actuator/info', '/build-revision', 'webRevision', 'expectedRevision')) {
    if (-not $startScript.Contains($requiredRevisionCheck)) {
        throw "Runtime build revision verification is missing: $requiredRevisionCheck"
    }
}

$configurationScriptPath = Join-Path $workspace 'ops/configuration.ps1'
. $configurationScriptPath
$environmentTemplatePath = Join-Path $workspace '.env.example'
if (-not (Test-Path -LiteralPath $environmentTemplatePath -PathType Leaf)) {
    throw 'The editable .env.example template is missing.'
}
$environmentTemplate = Read-DeploymentConfiguration -Path $environmentTemplatePath
foreach ($definition in Get-DeploymentConfigurationSchema) {
    if (-not $environmentTemplate.ContainsKey($definition.Name)) {
        throw ".env.example is missing $($definition.Name)."
    }
}
foreach ($secretName in @('POSTGRES_PASSWORD', 'VALKEY_PASSWORD', 'QR_SIGNING_SECRET',
        'DEVICE_CREDENTIAL_MASTER_KEY_BASE64', 'AUTH_JWT_SECRET_BASE64', 'WECHAT_APP_SECRET',
        'WECHAT_PRIMARY_API_V3_KEY', 'PLATFORM_ADMIN_PASSWORD')) {
    if (-not [string]::IsNullOrWhiteSpace([string]$environmentTemplate[$secretName])) {
        throw ".env.example must not contain a real or fixed secret: $secretName"
    }
}
$migrationRoot = Join-Path $workspace ("target/configuration-migration-test-$([guid]::NewGuid().ToString('N'))")
try {
    [void](New-Item -ItemType Directory -Path $migrationRoot -Force)
    $legacyPath = Join-Path $migrationRoot '.env.docker'
    [IO.File]::WriteAllText($legacyPath, "IDENTITY_PROVIDER_MODE=external`r`nOIDC_ISSUER_URI=https://obsolete.invalid`r`n")
    if (-not (Move-LegacyDeploymentConfiguration -Workspace $migrationRoot)) {
        throw 'Legacy .env.docker was not migrated.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $migrationRoot '.env') -PathType Leaf) -or
            (Test-Path -LiteralPath $legacyPath)) {
        throw 'Legacy configuration migration did not leave exactly one root .env file.'
    }
}
finally {
    if (Test-Path -LiteralPath $migrationRoot -PathType Container) {
        [IO.Directory]::Delete($migrationRoot, $true)
    }
}
$testRoot = Join-Path $workspace ("target/configuration-test-$([guid]::NewGuid().ToString('N'))")
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
$allowedPrefix = [IO.Path]::GetFullPath((Join-Path $workspace 'target/configuration-test-'))
if (-not $resolvedTestRoot.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create configuration test outside the workspace target directory.'
}
try {
    [void](New-Item -ItemType Directory -Path (Join-Path $testRoot 'apps/miniapp/src') -Force)
    $state = Initialize-DeploymentConfiguration -Workspace $testRoot
    if ([IO.Path]::GetFileName($state.Path) -ne '.env') {
        throw 'Deployment configuration must use the root .env file.'
    }
    if ($state.Values['IDENTITY_LOGIN_EVENT_RETENTION_DAYS'] -ne '180' -or
            $state.Values['IDENTITY_EXPIRED_TOKEN_RETENTION_DAYS'] -ne '7') {
        throw 'Fresh configuration must include bounded identity-data retention defaults.'
    }
    foreach ($required in @('PLATFORM_ADMIN_SUBJECT', 'PLATFORM_ADMIN_USERNAME', 'PLATFORM_ADMIN_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([string]$state.Values[$required])) {
            throw "Fresh configuration did not generate $required."
        }
    }
    $password = [string]$state.Values['PLATFORM_ADMIN_PASSWORD']
    if ($password.Length -lt 32 -or $password -cnotmatch '[A-Z]' -or $password -cnotmatch '[a-z]' -or
            $password -notmatch '[0-9]' -or $password -notmatch '[^A-Za-z0-9]') {
        throw 'Generated platform password does not meet the production password policy.'
    }
    $initialErrors = @(Test-DeploymentConfiguration -Workspace $testRoot -Values $state.Values)
    if ($initialErrors.Count -ne 0) {
        throw "Fresh built-in identity configuration was rejected: $($initialErrors -join '; ')"
    }

    $state.Values['APP_JWT_ISSUER'] = 'https://api.production.invalid'
    $state.Values['ALLOWED_ORIGINS'] = 'https://console.production.invalid'
    $state.Values['KEYCLOAK_IMAGE'] = 'obsolete-image'
    $state.Values['KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD'] = 'obsolete-secret'
    $state.Values['INITIAL_TENANT_ID'] = [guid]::NewGuid().ToString()
    Write-DeploymentConfiguration -Path $state.Path -Values $state.Values
    $state = Initialize-DeploymentConfiguration -Workspace $testRoot
    if ($state.Values['APP_JWT_ISSUER'] -ne 'https://api.production.invalid' -or
            $state.Values['ALLOWED_ORIGINS'] -ne 'https://console.production.invalid') {
        throw 'Built-in identity initialization overwrote production URLs.'
    }
    foreach ($obsolete in @('KEYCLOAK_IMAGE', 'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD', 'INITIAL_TENANT_ID')) {
        if ($state.Values.ContainsKey($obsolete)) {
            throw "Legacy identity configuration was not removed: $obsolete"
        }
    }

    $state.Values['CUSTOM_EQUALS_VALUE'] = 'first=second=third'
    Write-DeploymentConfiguration -Path $state.Path -Values $state.Values
    $roundTrip = Read-DeploymentConfiguration -Path $state.Path
    if ($roundTrip['CUSTOM_EQUALS_VALUE'] -ne 'first=second=third') {
        throw 'Configuration parser did not preserve equals signs in values.'
    }
    $roundTripErrors = @(Test-DeploymentConfiguration -Workspace $testRoot -Values $roundTrip)
    if ($roundTripErrors.Count -ne 0) {
        throw "Valid built-in identity configuration was rejected: $($roundTripErrors -join '; ')"
    }
    $miniappPath = Export-MiniappDeploymentConfiguration -Workspace $testRoot -Values $roundTrip
    $miniappText = Get-Content -LiteralPath $miniappPath -Raw
    if ($miniappText.Contains([string]$roundTrip['POSTGRES_PASSWORD']) -or $miniappText -notmatch 'module.exports') {
        throw 'Mini-program export is invalid or contains a server-side secret.'
    }
    $privateProjectPath = Join-Path $testRoot 'apps/miniapp/project.private.config.json'
    if (-not (Test-Path -LiteralPath $privateProjectPath -PathType Leaf)) {
        throw 'Mini-program export did not generate private project configuration.'
    }
    $privateProject = Get-Content -LiteralPath $privateProjectPath -Raw | ConvertFrom-Json
    if ($privateProject.setting.urlCheck -ne $true -or
            ([string]$privateProject.PSObject.Properties['appSecret']).Length -gt 0 -or
            (Get-Content -LiteralPath $privateProjectPath -Raw).Contains([string]$roundTrip['POSTGRES_PASSWORD'])) {
        throw 'Mini-program private project configuration is unsafe.'
    }
}
finally {
    if (Test-Path -LiteralPath $resolvedTestRoot -PathType Container) {
        [IO.Directory]::Delete($resolvedTestRoot, $true)
    }
}

$forbiddenRuntimeFiles = @(
    'docker-demo.cmd', 'simulator/device-simulator.mjs', 'ops/demo-charge.ps1',
    'ops/pilot-12-port.sql', 'ops/generate-pilot-qr.ps1', 'ops/keycloak.Dockerfile',
    'ops/postgres-init-keycloak.sh', 'ops/reconcile-bundled-identity.ps1'
)
foreach ($relativePath in $forbiddenRuntimeFiles) {
    if (Test-Path -LiteralPath (Join-Path $workspace $relativePath)) {
        throw "Obsolete or demo runtime artifact must not be shipped: $relativePath"
    }
}

Write-Host 'PASS: production runtime and unified configuration checks passed.'

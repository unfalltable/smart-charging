$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$startScriptPath = Join-Path $workspace 'ops/start-local.ps1'
$configurationScriptPath = Join-Path $workspace 'ops/configuration.ps1'
$managerScriptPath = Join-Path $workspace 'ops/manage-config.ps1'
$httpResponseScriptPath = Join-Path $workspace 'ops/http-response.ps1'
foreach ($scriptPath in @($startScriptPath, $configurationScriptPath, $managerScriptPath, $httpResponseScriptPath)) {
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
. $httpResponseScriptPath
$revisionFixture = 'revision-check'
$revisionBytes = [Text.Encoding]::UTF8.GetBytes("$revisionFixture`n")
if ((Convert-HttpContentToText -Content $revisionBytes).Trim() -ne $revisionFixture) {
    throw 'HTTP response byte content was not decoded as UTF-8 text.'
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
$provisionScript = Get-Content -LiteralPath $provisionScriptPath -Raw
foreach ($requiredSubjectLookup in @('Resolve-BundledPlatformAdminSubject', '/admin/realms/',
        'PLATFORM_ADMIN_USERNAME', 'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME')) {
    if (-not $provisionScript.Contains($requiredSubjectLookup)) {
        throw "Bundled tenant provisioning does not resolve the real Keycloak subject: $requiredSubjectLookup"
    }
}
$provisioningController = Get-Content -LiteralPath (Join-Path $workspace 'platform-core/src/main/java/io/smartcharge/platform/identity/TenantProvisioningController.java') -Raw
if ($provisioningController -notmatch 'on conflict \(tenant_id, user_id, role_code\) do update' -or
        $provisioningController -notmatch 'TENANT_PROVISIONING_RECONCILED') {
    throw 'Tenant provisioning must safely reconcile an existing tenant administrator membership.'
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
if (($startScript -notmatch 'without seeding business data') -or
        ($startScript -notmatch 'Startup did not create any tenant, station, device, tariff, order or payment data')) {
    throw 'Startup output must explicitly confirm that no business records were seeded.'
}
if ($startScript -notmatch "image inspect 'smart-charging-local-keycloak:latest'" -or
        $startScript -notmatch 'docker @compose ps --all --quiet keycloak' -or
        $startScript -notmatch "docker image tag .*'smart-charging-local-keycloak:latest'" -or
        $startScript -notmatch 'up --detach --no-build' -or
        $startScript -match 'up --detach --build') {
    throw 'Startup must reuse the optimized local Keycloak image instead of contacting its registry on every run.'
}
if ($configurationScript -notmatch 'PILOT_DEVICE_SECRET') {
    throw 'Unified configuration must reject legacy demo configuration.'
}
$stopScript = Get-Content -LiteralPath (Join-Path $workspace 'ops/stop-local.ps1') -Raw
if ($stopScript -notmatch 'Remove-Item -LiteralPath \$environmentFile') {
    throw 'Deleting legacy Docker data must also remove the obsolete local secret file.'
}
foreach ($cleanupOverride in @('ADMIN_WEB_PORT', 'KEYCLOAK_PORT', 'DEVICE_GATEWAY_PORT', 'NATS_MONITOR_PORT')) {
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
foreach ($requiredBuildRevisionSetting in @('APP_BUILD_REVISION', '/actuator/info', '/build-revision',
        'webRevision', 'expectedRevision', 'Convert-HttpContentToText')) {
    if ($startScript -notmatch [regex]::Escape($requiredBuildRevisionSetting) -and
            $compose -notmatch [regex]::Escape($requiredBuildRevisionSetting)) {
        throw "Runtime build revision verification is missing: $requiredBuildRevisionSetting"
    }
}
$nginxConfiguration = Get-Content -LiteralPath (Join-Path $workspace 'ops/nginx.local.conf') -Raw
if ($nginxConfiguration -notmatch 'location = /build-revision' -or
        $nginxConfiguration -notmatch '(?s)location = /build-revision.+?default_type text/plain') {
    throw 'The web build revision endpoint must explicitly return UTF-8 text.'
}
foreach ($requiredIdentitySetting in @('profiles: ["bundled-identity"]', 'start', '--optimized', '--import-realm',
        'KC_BOOTSTRAP_ADMIN_PASSWORD', 'OIDC_JWK_SET_URI', 'postgres-init-keycloak.sh',
        'INTERNAL_PROVISIONING_CLIENT_ID', 'IDENTITY_PROVIDER_MODE', 'PLATFORM_ADMIN_USERNAME',
        'INITIAL_TENANT_ID', 'smart-charging-local-keycloak:latest', 'service_completed_successfully')) {
    if (-not $compose.Contains($requiredIdentitySetting)) {
        throw "Bundled identity runtime setting is missing: $requiredIdentitySetting"
    }
}
if ($compose.Contains('start-dev')) {
    throw 'Bundled identity must not use Keycloak development mode.'
}
$keycloakDockerfile = Get-Content -LiteralPath (Join-Path $workspace 'ops/keycloak.Dockerfile') -Raw
if ($keycloakDockerfile -notmatch 'keycloak:26\.7\.4' -or $keycloakDockerfile -notmatch 'kc\.sh build') {
    throw 'Bundled identity must use a pinned, optimized Keycloak image.'
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
    if ($testState.Values['PLATFORM_ADMIN_USERNAME'] -notmatch '^platform-admin-[0-9a-f]{8}$' -or
            $testState.Values['KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME'] -notmatch '^keycloak-admin-[0-9a-f]{8}$') {
        throw 'Bootstrap administration usernames must be generated per deployment.'
    }
    $initialErrors = @(Test-DeploymentConfiguration -Workspace $testRoot -Values $testState.Values)
    if ($initialErrors.Count -ne 0 -or $testState.Values['OIDC_ISSUER_URI'] -notmatch '^http://127\.0\.0\.1:' -or
            $testState.Values['OIDC_JWK_SET_URI'] -notmatch '^http://keycloak:') {
        throw "Fresh configuration must provide a valid bundled identity service: $($initialErrors -join '; ')"
    }
    if ($testState.Values['VITE_OIDC_SCOPES'] -ne 'openid') {
        throw 'Bundled identity must request only the standard OIDC login scope.'
    }
    $realmPath = Export-BundledIdentityConfiguration -Workspace $testRoot -Values $testState.Values
    $realm = Get-Content -LiteralPath $realmPath -Raw | ConvertFrom-Json
    if ($realm.realm -ne 'smart-charging' -or $realm.clients[0].clientId -ne 'smart-charging-admin' -or
            $realm.roles.realm.name -notcontains 'admin') {
        throw 'Generated bundled identity realm is incomplete.'
    }
    $platformScope = @($realm.clientScopes | Where-Object { $_.name -eq 'smart-charging-api' })[0]
    if ($null -eq $platformScope -or
            $platformScope.protocolMappers.protocolMapper -notcontains 'oidc-audience-mapper' -or
            $platformScope.protocolMappers.protocolMapper -notcontains 'oidc-usermodel-attribute-mapper' -or
            $platformScope.protocolMappers.protocolMapper -notcontains 'oidc-usermodel-realm-role-mapper') {
        throw 'Generated platform client scope must include audience, tenant and realm-role claims.'
    }
    $platformAdmin = @($realm.users | Where-Object { $_.username -eq $testState.Values['PLATFORM_ADMIN_USERNAME'] })[0]
    if ($null -eq $platformAdmin -or $platformAdmin.realmRoles -contains 'internal') {
        throw 'The human platform administrator must not receive the machine-only internal authority.'
    }
    $realmText = Get-Content -LiteralPath $realmPath -Raw
    if ($realmText.Contains([string]$testState.Values['POSTGRES_PASSWORD']) -or
            $realmText.Contains([string]$testState.Values['KEYCLOAK_DB_PASSWORD']) -or
            $realmText.Contains([string]$testState.Values['KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD'])) {
        throw 'Generated realm import contains infrastructure secrets.'
    }
    $testState.Values['IDENTITY_PROVIDER_MODE'] = 'external'
    $testState.Values['OIDC_ISSUER_URI'] = ''
    $testState.Values['OIDC_JWK_SET_URI'] = ''
    $testState.Values['VITE_OIDC_AUTHORIZATION_ENDPOINT'] = ''
    $testState.Values['VITE_OIDC_TOKEN_ENDPOINT'] = ''
    $testState.Values['VITE_OIDC_CLIENT_ID'] = ''
    $externalErrors = @(Test-DeploymentConfiguration -Workspace $testRoot -Values $testState.Values)
    if (-not ($externalErrors -match 'OIDC_ISSUER_URI')) {
        throw 'External identity mode must reject missing real OIDC settings.'
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

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-ConfigurationDefinition {
    param(
        [string]$Name,
        [string]$Category,
        [bool]$Required,
        [bool]$Secret,
        [string]$DefaultValue,
        [string]$Generator,
        [string]$Description
    )

    return [pscustomobject]@{
        Name = $Name
        Category = $Category
        Required = $Required
        Secret = $Secret
        DefaultValue = $DefaultValue
        Generator = $Generator
        Description = $Description
    }
}

$script:DeploymentConfigurationSchema = @(
    New-ConfigurationDefinition 'POSTGRES_PASSWORD' 'Core secrets' $true $true '' 'Hex64' 'PostgreSQL bootstrap administrator password'
    New-ConfigurationDefinition 'DATABASE_MIGRATION_PASSWORD' 'Core secrets' $true $true '' 'Hex64' 'Dedicated database migration password'
    New-ConfigurationDefinition 'DATABASE_RUNTIME_PASSWORD' 'Core secrets' $true $true '' 'Hex64' 'Non-privileged database runtime password'
    New-ConfigurationDefinition 'VALKEY_PASSWORD' 'Core secrets' $true $true '' 'Hex64' 'Valkey access password'
    New-ConfigurationDefinition 'NATS_TOKEN' 'Core secrets' $true $true '' 'Hex64' 'Internal NATS access token'
    New-ConfigurationDefinition 'QR_SIGNING_SECRET' 'Core secrets' $true $true '' 'Hex64' 'Charging QR-code signing secret'
    New-ConfigurationDefinition 'DEVICE_CREDENTIAL_MASTER_KEY_BASE64' 'Core secrets' $true $true '' 'Base64_32' 'Device credential AES-256 master key'
    New-ConfigurationDefinition 'AUTH_JWT_SECRET_BASE64' 'Core secrets' $true $true '' 'Base64_32' 'Platform access-token signing key'

    New-ConfigurationDefinition 'APP_JWT_ISSUER' 'Identity' $true $false 'http://127.0.0.1:8088' '' 'Issuer for platform-issued tokens'
    New-ConfigurationDefinition 'API_JWT_AUDIENCE' 'Identity' $true $false 'smart-charging-api' '' 'API JWT audience'
    New-ConfigurationDefinition 'ALLOWED_ORIGINS' 'Identity' $true $false 'http://127.0.0.1:8088,http://localhost:8088' '' 'Comma-separated admin web origins'
    New-ConfigurationDefinition 'IDENTITY_LOGIN_EVENT_RETENTION_DAYS' 'Identity' $true $false '180' '' 'Administrator login-event retention in days'
    New-ConfigurationDefinition 'IDENTITY_EXPIRED_TOKEN_RETENTION_DAYS' 'Identity' $true $false '7' '' 'Expired refresh-token retention in days'

    New-ConfigurationDefinition 'PLATFORM_ADMIN_USERNAME' 'Built-in administrator' $false $false '' 'PlatformAdminUsername' 'Initial platform super-administrator username'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_DISPLAY_NAME' 'Built-in administrator' $false $false 'Platform Administrator' '' 'Initial platform super-administrator display name'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_EMAIL' 'Built-in administrator' $false $false '' '' 'Initial platform super-administrator email'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_SUBJECT' 'Built-in administrator' $false $false '' 'Uuid' 'Stable initial platform super-administrator subject'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_PASSWORD' 'Built-in administrator' $false $true '' 'StrongPassword' 'Initial one-time platform super-administrator password'

    New-ConfigurationDefinition 'WECHAT_IDENTITY_ENABLED' 'WeChat identity' $true $false 'false' '' 'Enable WeChat mini-program login'
    New-ConfigurationDefinition 'WECHAT_APP_ID' 'WeChat identity' $false $false '' '' 'WeChat mini-program AppID'
    New-ConfigurationDefinition 'WECHAT_APP_SECRET' 'WeChat identity' $false $true '' '' 'WeChat mini-program AppSecret'
    New-ConfigurationDefinition 'WECHAT_TENANT_CODE' 'WeChat identity' $false $false '' '' 'Default tenant code for WeChat users'
    New-ConfigurationDefinition 'MINIAPP_DEVTOOLS_CLI_PATH' 'WeChat identity' $false $false '' '' 'Optional path to the WeChat Developer Tools cli.bat'
    New-ConfigurationDefinition 'WECHAT_NOTIFICATION_ENABLED' 'WeChat notification' $true $false 'false' '' 'Enable WeChat subscription-message delivery'
    New-ConfigurationDefinition 'WECHAT_NOTIFICATION_TEMPLATES_JSON' 'WeChat notification' $false $false '{}' '' 'Server notification-type to WeChat-template JSON mapping'

    New-ConfigurationDefinition 'WECHAT_PAYMENT_ENABLED' 'WeChat payment' $true $false 'false' '' 'Enable WeChat Pay key material'
    New-ConfigurationDefinition 'WECHAT_PAYMENT_DIRECTORY' 'WeChat payment' $true $false 'runtime-secrets/wechat-pay' '' 'WeChat Pay key-material directory'
    New-ConfigurationDefinition 'WECHAT_PRIMARY_MERCHANT_SERIAL' 'WeChat payment' $false $false '' '' 'Merchant API certificate serial number'
    New-ConfigurationDefinition 'WECHAT_PRIMARY_API_V3_KEY' 'WeChat payment' $false $true '' '' 'WeChat Pay APIv3 key'
    New-ConfigurationDefinition 'WECHAT_PRIMARY_PUBLIC_KEY_ID' 'WeChat payment' $false $false '' '' 'WeChat Pay public-key ID'
    New-ConfigurationDefinition 'PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS' 'WeChat payment' $true $false '3000' '' 'Provider-approved maximum profit-sharing ratio in basis points'
    New-ConfigurationDefinition 'PAYMENT_INTENT_EXPIRE_MINUTES' 'WeChat payment' $true $false '30' '' 'Unpaid payment intent lifetime, from 2 to 120 minutes'

    New-ConfigurationDefinition 'DEVICE_GATEWAY_ENABLED' 'Device gateway' $true $false 'false' '' 'Start the device long-connection gateway'
    New-ConfigurationDefinition 'DEVICE_GATEWAY_BIND_ADDRESS' 'Device gateway' $true $false '127.0.0.1' '' 'Device gateway bind address'
    New-ConfigurationDefinition 'DEVICE_TLS_DIRECTORY' 'Device gateway' $false $false '' '' 'Device gateway mutual-TLS directory'

    New-ConfigurationDefinition 'MINIAPP_DEVELOP_API_BASE' 'Mini program' $false $false '' '' 'Develop-version HTTPS API base URL'
    New-ConfigurationDefinition 'MINIAPP_DEVELOP_TENANT_CODE' 'Mini program' $false $false '' '' 'Develop-version tenant code'
    New-ConfigurationDefinition 'MINIAPP_DEVELOP_NOTIFICATION_TEMPLATE_IDS' 'Mini program' $false $false '[]' '' 'Develop-version subscription template-ID JSON array'
    New-ConfigurationDefinition 'MINIAPP_TRIAL_API_BASE' 'Mini program' $false $false '' '' 'Trial-version HTTPS API base URL'
    New-ConfigurationDefinition 'MINIAPP_TRIAL_TENANT_CODE' 'Mini program' $false $false '' '' 'Trial-version tenant code'
    New-ConfigurationDefinition 'MINIAPP_TRIAL_NOTIFICATION_TEMPLATE_IDS' 'Mini program' $false $false '[]' '' 'Trial-version subscription template-ID JSON array'
    New-ConfigurationDefinition 'MINIAPP_RELEASE_API_BASE' 'Mini program' $false $false '' '' 'Release-version HTTPS API base URL'
    New-ConfigurationDefinition 'MINIAPP_RELEASE_TENANT_CODE' 'Mini program' $false $false '' '' 'Release-version tenant code'
    New-ConfigurationDefinition 'MINIAPP_RELEASE_NOTIFICATION_TEMPLATE_IDS' 'Mini program' $false $false '[]' '' 'Release-version subscription template-ID JSON array'

    New-ConfigurationDefinition 'ADMIN_WEB_PORT' 'Local ports' $true $false '8088' '' 'Admin web port'
    New-ConfigurationDefinition 'CORE_PORT' 'Local ports' $true $false '18080' '' 'Core API direct port'
    New-ConfigurationDefinition 'DEVICE_GATEWAY_PORT' 'Local ports' $true $false '9000' '' 'Device protocol port'
    New-ConfigurationDefinition 'DEVICE_MANAGEMENT_PORT' 'Local ports' $true $false '9001' '' 'Device gateway management port'
    New-ConfigurationDefinition 'POSTGRES_PORT' 'Local ports' $true $false '15432' '' 'PostgreSQL mapped port'
    New-ConfigurationDefinition 'VALKEY_PORT' 'Local ports' $true $false '16379' '' 'Valkey mapped port'
    New-ConfigurationDefinition 'NATS_PORT' 'Local ports' $true $false '14222' '' 'NATS mapped port'
    New-ConfigurationDefinition 'NATS_MONITOR_PORT' 'Local ports' $true $false '18222' '' 'NATS monitoring port'

    New-ConfigurationDefinition 'PUBLIC_HOST' 'Production' $false $false '' '' 'Public DNS hostname; enables the managed HTTPS entrypoint'
    New-ConfigurationDefinition 'HTTP_PORT' 'Production' $true $false '80' '' 'Production HTTPS redirect and ACME port'
    New-ConfigurationDefinition 'HTTPS_PORT' 'Production' $true $false '443' '' 'Production HTTPS port'
    New-ConfigurationDefinition 'BACKUP_DIRECTORY' 'Production' $true $false 'runtime-secrets/backups' '' 'Private coordinated-backup directory'

    New-ConfigurationDefinition 'DATABASE_POOL_SIZE' 'Performance' $true $false '10' '' 'Maximum database pool size'
    New-ConfigurationDefinition 'DATABASE_POOL_MIN_IDLE' 'Performance' $true $false '2' '' 'Minimum idle database connections'
    New-ConfigurationDefinition 'ACCESS_TOKEN_MINUTES' 'Performance' $true $false '15' '' 'Access-token lifetime in minutes'
    New-ConfigurationDefinition 'REFRESH_TOKEN_DAYS' 'Performance' $true $false '30' '' 'Refresh-token lifetime in days'
    New-ConfigurationDefinition 'RATE_LIMIT_DEFAULT_PER_MINUTE' 'Performance' $true $false '600' '' 'Normal API requests per minute'
    New-ConfigurationDefinition 'RATE_LIMIT_PUBLIC_PER_MINUTE' 'Performance' $true $false '120' '' 'Public API requests per minute'
    New-ConfigurationDefinition 'RATE_LIMIT_LOGIN_PER_MINUTE' 'Performance' $true $false '20' '' 'Login requests per minute'
    New-ConfigurationDefinition 'TRACING_SAMPLE_RATE' 'Performance' $true $false '0.1' '' 'Distributed tracing sample rate from 0 to 1'
    New-ConfigurationDefinition 'OUTBOX_PUBLISHER_DELAY_MS' 'Performance' $true $false '500' '' 'Outbox publisher polling delay in milliseconds'
    New-ConfigurationDefinition 'APPLICATION_LOG_LEVEL' 'Performance' $true $false 'INFO' '' 'Application log level'
)

function Get-DeploymentConfigurationSchema {
    return @($script:DeploymentConfigurationSchema)
}

function Get-DeploymentConfigurationPath {
    param([Parameter(Mandatory = $true)][string]$Workspace)
    return Join-Path $Workspace '.env'
}

function Protect-GeneratedConfigurationPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [ValidateSet('600', '700')][string]$Mode = '600'
    )

    # Protect only our generated configuration/backup paths, not user certificates.
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Unix) {
        & chmod $Mode -- $Path
        if ($LASTEXITCODE -ne 0) { throw "Could not restrict permissions on generated private configuration: $Path" }
    }
}

function Move-LegacyDeploymentConfiguration {
    param([Parameter(Mandatory = $true)][string]$Workspace)

    $path = Get-DeploymentConfigurationPath -Workspace $Workspace
    $legacyPath = Join-Path $Workspace '.env.docker'
    if ((Test-Path -LiteralPath $path -PathType Leaf) -or
            -not (Test-Path -LiteralPath $legacyPath -PathType Leaf)) {
        return $false
    }
    Move-Item -LiteralPath $legacyPath -Destination $path
    Write-Host 'Migrated .env.docker to .env. All existing credentials were preserved.' -ForegroundColor Yellow
    return $true
}

function New-RandomBytes {
    param([Parameter(Mandatory = $true)][int]$Length)

    $bytes = New-Object byte[] $Length
    $provider = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $provider.GetBytes($bytes)
    }
    finally {
        $provider.Dispose()
    }
    return $bytes
}

function New-GeneratedConfigurationValue {
    param([Parameter(Mandatory = $true)]$Definition)

    switch ($Definition.Generator) {
        'Hex64' {
            return -join ((New-RandomBytes -Length 32) | ForEach-Object { $_.ToString('x2') })
        }
        'Base64_32' {
            return [Convert]::ToBase64String((New-RandomBytes -Length 32))
        }
        'StrongPassword' {
            $random = -join ((New-RandomBytes -Length 30) | ForEach-Object { $_.ToString('x2') })
            return "Aa7!$random"
        }
        'Uuid' {
            return [guid]::NewGuid().ToString()
        }
        'PlatformAdminUsername' {
            $suffix = -join ((New-RandomBytes -Length 4) | ForEach-Object { $_.ToString('x2') })
            return "platform-admin-$suffix"
        }
        default {
            return $Definition.DefaultValue
        }
    }
}

function Read-DeploymentConfiguration {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $values
    }

    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $lineNumber++
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
            continue
        }

        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw "Invalid configuration line $lineNumber in $Path. Expected NAME=value."
        }

        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($value.StartsWith("'")) {
            if ($value -notmatch "^'((?:\\'|[^'])*)'\s*(?:#.*)?$") {
                throw "Invalid single-quoted value '$name' on line $lineNumber in $Path."
            }
            $value = $Matches[1].Replace("\'", "'")
        }
        elseif ($value.StartsWith('"')) {
            throw "Use unquoted values or literal single quotes for '$name'; double-quoted interpolation is not supported."
        }
        else {
            $value = ($value -replace '\s+#.*$', '').TrimEnd()
            if ($value.Contains('$')) {
                throw "Wrap '$name' in single quotes so Docker does not expand dollar signs in the value."
            }
        }
        if ($name -notmatch '^[A-Z][A-Z0-9_]*$') {
            throw "Invalid configuration key '$name' on line $lineNumber in $Path."
        }
        if ($value.Contains("`r") -or $value.Contains("`n")) {
            throw "Configuration value '$name' cannot contain line breaks."
        }
        if ($values.ContainsKey($name)) {
            throw "Duplicate configuration key '$name' in $Path."
        }
        $values[$name] = $value
    }
    return $values
}

function Write-DeploymentConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][hashtable]$Values
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# Smart Charging deployment configuration. Keep this file private and out of Git.')
    $lines.Add('# Use .\config-manager.cmd status or validate after editing.')
    $handled = @{}
    $lastCategory = ''

    foreach ($definition in $script:DeploymentConfigurationSchema) {
        if ($definition.Category -ne $lastCategory) {
            $lines.Add('')
            $lines.Add("# [$($definition.Category)]")
            $lastCategory = $definition.Category
        }
        $value = if ($Values.ContainsKey($definition.Name)) { [string]$Values[$definition.Name] } else { '' }
        if ($value.Contains("`r") -or $value.Contains("`n")) {
            throw "Configuration value '$($definition.Name)' cannot contain line breaks."
        }
        $literalValue = $value.Replace("'", "\'")
        $lines.Add("$($definition.Name)='$literalValue'")
        $handled[$definition.Name] = $true
    }

    $additional = @($Values.Keys | Where-Object { -not $handled.ContainsKey($_) } | Sort-Object)
    if ($additional.Count -gt 0) {
        $lines.Add('')
        $lines.Add('# [Additional configuration]')
        foreach ($name in $additional) {
            $value = [string]$Values[$name]
            if ($value.Contains("`r") -or $value.Contains("`n")) {
                throw "Configuration value '$name' cannot contain line breaks."
            }
            $literalValue = $value.Replace("'", "\'")
            $lines.Add("$name='$literalValue'")
        }
    }

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $directory -Force)
    }
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $lines, $utf8WithoutBom)
    Protect-GeneratedConfigurationPath -Path $Path
}

function Set-LocalIdentityConfiguration {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    $adminPort = [string]$Values['ADMIN_WEB_PORT']
    $changed = $false
    $localDefaults = [ordered]@{
        APP_JWT_ISSUER = "http://127.0.0.1:$adminPort"
        ALLOWED_ORIGINS = "http://127.0.0.1:$adminPort,http://localhost:$adminPort"
    }
    foreach ($entry in $localDefaults.GetEnumerator()) {
        if ([string]::IsNullOrWhiteSpace([string]$Values[$entry.Key])) {
            $Values[$entry.Key] = $entry.Value
            $changed = $true
        }
    }
    return $changed
}

function Initialize-DeploymentConfiguration {
    param([Parameter(Mandatory = $true)][string]$Workspace)

    $migrated = Move-LegacyDeploymentConfiguration -Workspace $Workspace
    $path = Get-DeploymentConfigurationPath -Workspace $Workspace
    $created = -not (Test-Path -LiteralPath $path -PathType Leaf)
    $values = Read-DeploymentConfiguration -Path $path
    if ($values.ContainsKey('PILOT_DEVICE_SECRET')) {
        throw 'Legacy pilot/demo configuration detected. Delete .env and run config-manager.cmd init to create a clean deployment configuration.'
    }

    $changed = $created
    foreach ($definition in $script:DeploymentConfigurationSchema) {
        $missing = -not $values.ContainsKey($definition.Name)
        $generatedValueMissing = -not [string]::IsNullOrWhiteSpace($definition.Generator) -and
            [string]::IsNullOrWhiteSpace([string]$values[$definition.Name])
        if ($missing -or $generatedValueMissing) {
            $values[$definition.Name] = New-GeneratedConfigurationValue -Definition $definition
            $changed = $true
        }
    }

    $configuredAdminPassword = [string]$values['PLATFORM_ADMIN_PASSWORD']
    if ($values.ContainsKey('KEYCLOAK_IMAGE') -and
            ($configuredAdminPassword -cnotmatch '[A-Z]' -or $configuredAdminPassword -notmatch '[^A-Za-z0-9]')) {
        $adminPasswordDefinition = $script:DeploymentConfigurationSchema |
            Where-Object { $_.Name -eq 'PLATFORM_ADMIN_PASSWORD' } | Select-Object -First 1
        $values['PLATFORM_ADMIN_PASSWORD'] = New-GeneratedConfigurationValue -Definition $adminPasswordDefinition
        $changed = $true
    }
    $obsoleteIdentityNames = @(
        'IDENTITY_INVITATION_REDIRECT_URI', 'IDENTITY_EMAIL_ENABLED', 'IDENTITY_SMTP_HOST',
        'IDENTITY_SMTP_PORT', 'IDENTITY_SMTP_FROM', 'IDENTITY_SMTP_FROM_DISPLAY_NAME',
        'IDENTITY_SMTP_USERNAME', 'IDENTITY_SMTP_PASSWORD', 'IDENTITY_SMTP_STARTTLS',
        'KEYCLOAK_IMAGE', 'KEYCLOAK_PORT', 'KEYCLOAK_REALM', 'KEYCLOAK_CLIENT_ID',
        'KEYCLOAK_PROVISIONING_CLIENT_ID', 'KEYCLOAK_PROVISIONING_CLIENT_SECRET',
        'KEYCLOAK_DB_PASSWORD', 'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME',
        'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD', 'KEYCLOAK_IMPORT_DIRECTORY', 'INITIAL_TENANT_ID',
        'IDENTITY_PROVIDER_MODE', 'OIDC_ISSUER_URI', 'OIDC_JWK_SET_URI',
        'VITE_OIDC_AUTHORIZATION_ENDPOINT', 'VITE_OIDC_TOKEN_ENDPOINT', 'VITE_OIDC_CLIENT_ID',
        'VITE_OIDC_REDIRECT_URI', 'VITE_OIDC_SCOPES', 'IDENTITY_INVITATION_LIFESPAN_HOURS'
    )
    foreach ($name in $obsoleteIdentityNames) {
        if ($values.ContainsKey($name)) {
            $values.Remove($name)
            $changed = $true
        }
    }
    if (Set-LocalIdentityConfiguration -Values $values) {
        $changed = $true
    }

    if ($changed) {
        Write-DeploymentConfiguration -Path $path -Values $values
    }
    Protect-GeneratedConfigurationPath -Path $path
    $merchantEnvironment = Export-MerchantEnvironmentConfiguration -Workspace $Workspace -Values $values
    [Environment]::SetEnvironmentVariable('CORE_MERCHANT_ENV_FILE', $merchantEnvironment, 'Process')

    return [pscustomobject]@{
        Path = $path
        Values = $values
        Created = $created
        Changed = $changed
        Migrated = $migrated
    }
}

function Get-ConfigurationBoolean {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not $Values.ContainsKey($Name)) {
        return $false
    }
    return [string]$Values[$Name] -eq 'true'
}

function Set-DeploymentProcessConfiguration {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    # Prevent unrelated host-shell variables from silently overriding the root .env.
    foreach ($definition in $script:DeploymentConfigurationSchema) {
        [Environment]::SetEnvironmentVariable($definition.Name, [string]$Values[$definition.Name], 'Process')
    }
}

function Test-AbsoluteSecureUrl {
    param([string]$Value)

    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri)) {
        return $false
    }
    if ($uri.Scheme -eq 'https') {
        return $true
    }
    if ($uri.Scheme -ne 'http') {
        return $false
    }
    return @('127.0.0.1', 'localhost', '::1') -contains $uri.Host -or $uri.Host.EndsWith('.localhost')
}

function Test-AbsoluteHttpsUrl {
    param([string]$Value)

    $uri = $null
    return [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -and $uri.Scheme -eq 'https'
}

function Resolve-ConfigurationDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Workspace,
        [string]$ConfiguredPath
    )

    if ([string]::IsNullOrWhiteSpace($ConfiguredPath)) {
        return ''
    }
    if ([System.IO.Path]::IsPathRooted($ConfiguredPath)) {
        return [System.IO.Path]::GetFullPath($ConfiguredPath)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $Workspace $ConfiguredPath))
}

function Test-DeploymentConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$Workspace,
        [Parameter(Mandatory = $true)][hashtable]$Values
    )

    $errors = New-Object System.Collections.Generic.List[string]
    foreach ($definition in $script:DeploymentConfigurationSchema) {
        if ($definition.Required -and (-not $Values.ContainsKey($definition.Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$definition.Name]))) {
            $errors.Add("$($definition.Name): required value is missing")
        }
    }

    foreach ($name in @('WECHAT_IDENTITY_ENABLED', 'WECHAT_NOTIFICATION_ENABLED', 'WECHAT_PAYMENT_ENABLED', 'DEVICE_GATEWAY_ENABLED')) {
        if ($Values.ContainsKey($name) -and @('true', 'false') -notcontains [string]$Values[$name]) {
            $errors.Add("$($name): value must be true or false")
        }
    }

    foreach ($name in @('APP_JWT_ISSUER')) {
        if ($Values.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$name]) -and -not (Test-AbsoluteSecureUrl -Value ([string]$Values[$name]))) {
            $errors.Add("$($name): use an absolute HTTPS URL; HTTP is allowed only for loopback development")
        }
    }
    if ($Values.ContainsKey('ALLOWED_ORIGINS')) {
        foreach ($origin in ([string]$Values['ALLOWED_ORIGINS']).Split(',')) {
            if (-not (Test-AbsoluteSecureUrl -Value $origin.Trim())) {
                $errors.Add('ALLOWED_ORIGINS: every origin must be an absolute HTTPS URL; HTTP is allowed only for loopback development')
                break
            }
        }
    }

    foreach ($name in @('POSTGRES_PASSWORD', 'DATABASE_MIGRATION_PASSWORD', 'DATABASE_RUNTIME_PASSWORD', 'VALKEY_PASSWORD', 'NATS_TOKEN', 'QR_SIGNING_SECRET')) {
        if ($Values.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$name]) -and
                ([string]$Values[$name]).Length -lt 32) {
            $errors.Add("$($name): secret must contain at least 32 characters")
        }
    }
    $databaseSecrets = @('POSTGRES_PASSWORD', 'DATABASE_MIGRATION_PASSWORD', 'DATABASE_RUNTIME_PASSWORD') |
        ForEach-Object { [string]$Values[$_] }
    if (@($databaseSecrets | Select-Object -Unique).Count -ne 3) {
        $errors.Add('Database administrator, migration and runtime passwords must be different')
    }

    foreach ($name in @('DEVICE_CREDENTIAL_MASTER_KEY_BASE64', 'AUTH_JWT_SECRET_BASE64')) {
        try {
            $decoded = [Convert]::FromBase64String([string]$Values[$name])
            if ($decoded.Length -ne 32) {
                $errors.Add("$($name): decoded key must be exactly 32 bytes")
            }
        }
        catch {
            $errors.Add("$($name): value must be valid Base64")
        }
    }

    $ports = @('ADMIN_WEB_PORT', 'CORE_PORT', 'DEVICE_GATEWAY_PORT', 'DEVICE_MANAGEMENT_PORT', 'POSTGRES_PORT', 'VALKEY_PORT', 'NATS_PORT', 'NATS_MONITOR_PORT', 'HTTP_PORT', 'HTTPS_PORT')
    $usedPorts = @{}
    foreach ($name in $ports) {
        $port = 0
        if (-not [int]::TryParse([string]$Values[$name], [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            $errors.Add("$($name): port must be between 1 and 65535")
        }
        elseif ($usedPorts.ContainsKey($port)) {
            $errors.Add("$($name): port $port is already used by $($usedPorts[$port])")
        }
        else {
            $usedPorts[$port] = $name
        }
    }

    $publicHost = [string]$Values['PUBLIC_HOST']
    if (-not [string]::IsNullOrWhiteSpace($publicHost)) {
        if ($publicHost -notmatch '^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$') {
            $errors.Add('PUBLIC_HOST: use one public DNS hostname without a scheme, port, path, wildcard or spaces')
        }
        if ([string]$Values['APP_JWT_ISSUER'] -ne "https://$publicHost") {
            $errors.Add('APP_JWT_ISSUER: when PUBLIC_HOST is set, use https://PUBLIC_HOST')
        }
        if ([string]$Values['ALLOWED_ORIGINS'] -ne "https://$publicHost") {
            $errors.Add('ALLOWED_ORIGINS: when PUBLIC_HOST is set, use only https://PUBLIC_HOST')
        }
        if ([string]$Values['HTTP_PORT'] -ne '80' -or [string]$Values['HTTPS_PORT'] -ne '443') {
            $errors.Add('HTTP_PORT / HTTPS_PORT: the managed public entrypoint requires standard ports 80 / 443')
        }
    }

    foreach ($name in @('DATABASE_POOL_SIZE', 'DATABASE_POOL_MIN_IDLE', 'ACCESS_TOKEN_MINUTES', 'REFRESH_TOKEN_DAYS', 'RATE_LIMIT_DEFAULT_PER_MINUTE', 'RATE_LIMIT_PUBLIC_PER_MINUTE', 'RATE_LIMIT_LOGIN_PER_MINUTE', 'OUTBOX_PUBLISHER_DELAY_MS', 'IDENTITY_LOGIN_EVENT_RETENTION_DAYS', 'IDENTITY_EXPIRED_TOKEN_RETENTION_DAYS', 'PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS', 'PAYMENT_INTENT_EXPIRE_MINUTES')) {
        $number = 0
        if (-not [int]::TryParse([string]$Values[$name], [ref]$number) -or $number -lt 1) {
            $errors.Add("$($name): value must be a positive integer")
        }
    }
    $paymentIntentMinutes = 0
    if ([int]::TryParse([string]$Values['PAYMENT_INTENT_EXPIRE_MINUTES'], [ref]$paymentIntentMinutes) -and
            ($paymentIntentMinutes -lt 2 -or $paymentIntentMinutes -gt 120)) {
        $errors.Add('PAYMENT_INTENT_EXPIRE_MINUTES: value must be between 2 and 120 minutes')
    }
    $profitSharingLimit = 0
    if ([int]::TryParse([string]$Values['PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS'], [ref]$profitSharingLimit) -and
            $profitSharingLimit -gt 10000) {
        $errors.Add('PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS: value cannot exceed 10000 basis points')
    }
    $sampleRate = 0.0
    if (-not [double]::TryParse([string]$Values['TRACING_SAMPLE_RATE'], [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$sampleRate) -or $sampleRate -lt 0 -or $sampleRate -gt 1) {
        $errors.Add('TRACING_SAMPLE_RATE: value must be between 0 and 1')
    }

    foreach ($name in @('PLATFORM_ADMIN_USERNAME', 'PLATFORM_ADMIN_DISPLAY_NAME',
            'PLATFORM_ADMIN_SUBJECT', 'PLATFORM_ADMIN_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([string]$Values[$name])) {
            $errors.Add("$($name): required for the built-in platform administrator")
        }
    }
    if ([string]$Values['PLATFORM_ADMIN_USERNAME'] -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$') {
        $errors.Add('PLATFORM_ADMIN_USERNAME: use 3 to 64 letters, digits, dots, underscores or hyphens')
    }
    $adminPassword = [string]$Values['PLATFORM_ADMIN_PASSWORD']
    if ([Text.Encoding]::UTF8.GetByteCount($adminPassword) -gt 72) {
        $errors.Add('PLATFORM_ADMIN_PASSWORD: UTF-8 encoding must not exceed 72 bytes (bcrypt limit)')
    }
    if ($adminPassword.Length -lt 12 -or $adminPassword -cnotmatch '[A-Z]' -or
            $adminPassword -cnotmatch '[a-z]' -or $adminPassword -notmatch '[0-9]' -or
            $adminPassword -notmatch '[^A-Za-z0-9]') {
        $errors.Add('PLATFORM_ADMIN_PASSWORD: use at least 12 characters with upper/lower case, number and symbol')
    }
    $identifier = [guid]::Empty
    if (-not [guid]::TryParse([string]$Values['PLATFORM_ADMIN_SUBJECT'], [ref]$identifier) -or
            $identifier -eq [guid]::Empty) {
        $errors.Add('PLATFORM_ADMIN_SUBJECT: value must be a non-zero UUID')
    }

    if (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_IDENTITY_ENABLED') {
        foreach ($name in @('WECHAT_APP_ID', 'WECHAT_APP_SECRET', 'WECHAT_TENANT_CODE')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values[$name])) {
                $errors.Add("$($name): required when WECHAT_IDENTITY_ENABLED=true")
            }
        }
        if ([string]$Values['WECHAT_APP_ID'] -notmatch '^wx[0-9a-fA-F]{16}$') {
            $errors.Add('WECHAT_APP_ID: expected wx followed by 16 hexadecimal characters')
        }
        if ([string]$Values['WECHAT_APP_SECRET'] -notmatch '^[0-9a-fA-F]{32}$') {
            $errors.Add('WECHAT_APP_SECRET: expected the 32-character secret from WeChat')
        }
        if ([string]$Values['WECHAT_TENANT_CODE'] -notmatch '^[a-z0-9][a-z0-9-]{1,62}$') {
            $errors.Add('WECHAT_TENANT_CODE: use 2 to 63 lowercase letters, digits or hyphens')
        }
    }
    if (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_NOTIFICATION_ENABLED') {
        if (-not (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_IDENTITY_ENABLED')) {
            $errors.Add('WECHAT_NOTIFICATION_ENABLED: WeChat identity must be enabled first')
        }
        try {
            $templatesJson = [string]$Values['WECHAT_NOTIFICATION_TEMPLATES_JSON']
            $templates = $templatesJson | ConvertFrom-Json
            if (-not $templatesJson.Trim().StartsWith('{') -or -not $templatesJson.Trim().EndsWith('}') -or
                    $null -eq $templates -or $templates.PSObject.Properties.Count -eq 0) {
                $errors.Add('WECHAT_NOTIFICATION_TEMPLATES_JSON: notification template mapping cannot be empty when notifications are enabled')
            }
        }
        catch {
            $errors.Add('WECHAT_NOTIFICATION_TEMPLATES_JSON: value must be a valid JSON object')
        }
    }

    if (Get-ConfigurationBoolean -Values $Values -Name 'DEVICE_GATEWAY_ENABLED') {
        $tlsDirectory = Resolve-ConfigurationDirectory -Workspace $Workspace -ConfiguredPath ([string]$Values['DEVICE_TLS_DIRECTORY'])
        if ([string]::IsNullOrWhiteSpace($tlsDirectory)) {
            $errors.Add('DEVICE_TLS_DIRECTORY: required when DEVICE_GATEWAY_ENABLED=true')
        }
        else {
            foreach ($fileName in @('tls.crt', 'tls.key', 'ca.crt')) {
                if (-not (Test-Path -LiteralPath (Join-Path $tlsDirectory $fileName) -PathType Leaf)) {
                    $errors.Add("DEVICE_TLS_DIRECTORY: missing $fileName")
                }
            }
        }
    }

    if (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_PAYMENT_ENABLED') {
        foreach ($name in @('WECHAT_PRIMARY_MERCHANT_SERIAL', 'WECHAT_PRIMARY_API_V3_KEY', 'WECHAT_PRIMARY_PUBLIC_KEY_ID')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values[$name])) {
                $errors.Add("$($name): required when WECHAT_PAYMENT_ENABLED=true")
            }
        }
        $paymentDirectory = Resolve-ConfigurationDirectory -Workspace $Workspace -ConfiguredPath ([string]$Values['WECHAT_PAYMENT_DIRECTORY'])
        foreach ($fileName in @('merchant-private-key.pem', 'wechat-pay-public-key.pem')) {
            if ([string]::IsNullOrWhiteSpace($paymentDirectory) -or -not (Test-Path -LiteralPath (Join-Path $paymentDirectory $fileName) -PathType Leaf)) {
                $errors.Add("WECHAT_PAYMENT_DIRECTORY: missing $fileName")
            }
        }
    }

    $merchantSuffixes = @('PRIVATE_KEY_PATH', 'MERCHANT_SERIAL', 'API_V3_KEY', 'PUBLIC_KEY_PATH', 'PUBLIC_KEY_ID')
    $merchantPrefixes = @(Get-CustomMerchantConfigurationPrefixes -Values $Values)
    foreach ($prefix in $merchantPrefixes) {
        foreach ($suffix in $merchantSuffixes) {
            $name = "${prefix}_$suffix"
            if ([string]::IsNullOrWhiteSpace([string]$Values[$name])) {
                $errors.Add("$($name): all five values are required for a configured merchant prefix")
            }
        }
        if (([string]$Values["${prefix}_API_V3_KEY"]).Length -ne 32) {
            $errors.Add("$($prefix)_API_V3_KEY: the provider APIv3 key must contain exactly 32 characters")
        }
        foreach ($suffix in @('PRIVATE_KEY_PATH', 'PUBLIC_KEY_PATH')) {
            $name = "${prefix}_$suffix"
            $path = [string]$Values[$name]
            if ($path -notmatch '^/run/secrets/wechat-pay/[A-Za-z0-9_./-]+$' -or $path -match '(^|/)\.\.(/|$)') {
                $errors.Add("$($name): use a container path inside /run/secrets/wechat-pay/")
                continue
            }
            $materialDirectory = Resolve-ConfigurationDirectory -Workspace $Workspace -ConfiguredPath ([string]$Values['WECHAT_PAYMENT_DIRECTORY'])
            $relative = $path.Substring('/run/secrets/wechat-pay/'.Length)
            if (-not (Test-Path -LiteralPath (Join-Path $materialDirectory $relative) -PathType Leaf)) {
                $errors.Add("$($name): the referenced file is missing from WECHAT_PAYMENT_DIRECTORY")
            }
        }
    }

    foreach ($environment in @('DEVELOP', 'TRIAL', 'RELEASE')) {
        $apiName = "MINIAPP_${environment}_API_BASE"
        $tenantName = "MINIAPP_${environment}_TENANT_CODE"
        $templatesName = "MINIAPP_${environment}_NOTIFICATION_TEMPLATE_IDS"
        $apiValue = [string]$Values[$apiName]
        $tenantValue = [string]$Values[$tenantName]
        if (-not [string]::IsNullOrWhiteSpace($apiValue) -or -not [string]::IsNullOrWhiteSpace($tenantValue)) {
            if ([string]::IsNullOrWhiteSpace($apiValue) -or -not (Test-AbsoluteHttpsUrl -Value $apiValue) -or $apiValue -notmatch '/api/v1/?$') {
                $errors.Add("$($apiName): a valid HTTPS API URL ending in /api/v1 is required for a configured mini program profile")
            }
            if ([string]::IsNullOrWhiteSpace($tenantValue) -or $tenantValue -notmatch '^[a-z0-9][a-z0-9-]{1,62}$') {
                $errors.Add("$($tenantName): a valid tenant code is required for a configured mini program profile")
            }
            if (-not (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_IDENTITY_ENABLED')) {
                $errors.Add("$($apiName): WeChat identity must be enabled for a configured mini program profile")
            }
            elseif ($tenantValue -ne [string]$Values['WECHAT_TENANT_CODE']) {
                $errors.Add("$($tenantName): must match WECHAT_TENANT_CODE")
            }
        }
        try {
            $templateIdsJson = [string]$Values[$templatesName]
            if (-not $templateIdsJson.Trim().StartsWith('[') -or -not $templateIdsJson.Trim().EndsWith(']')) {
                throw 'not an array'
            }
            $templateIds = @(($templateIdsJson | ConvertFrom-Json))
            foreach ($templateId in $templateIds) {
                if ($templateId -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$templateId)) {
                    throw 'invalid item'
                }
            }
        }
        catch {
            $errors.Add("$($templatesName): value must be a JSON array of non-empty strings")
        }
    }

    return @($errors)
}

function Get-CustomMerchantConfigurationPrefixes {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    $prefixes = @{}
    foreach ($name in $Values.Keys) {
        if ([string]$name -match '^([A-Z][A-Z0-9_]{1,80})_(PRIVATE_KEY_PATH|MERCHANT_SERIAL|API_V3_KEY|PUBLIC_KEY_PATH|PUBLIC_KEY_ID)$' -and
                $Matches[1] -ne 'WECHAT_PRIMARY') {
            $prefixes[$Matches[1]] = $true
        }
    }
    return @($prefixes.Keys | Sort-Object)
}

function Export-MerchantEnvironmentConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$Workspace,
        [Parameter(Mandatory = $true)][hashtable]$Values
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# Generated from root .env for the core container only. Never commit or edit.')
    foreach ($prefix in Get-CustomMerchantConfigurationPrefixes -Values $Values) {
        foreach ($suffix in @('PRIVATE_KEY_PATH', 'MERCHANT_SERIAL', 'API_V3_KEY', 'PUBLIC_KEY_PATH', 'PUBLIC_KEY_ID')) {
            $name = "${prefix}_$suffix"
            $value = [string]$Values[$name]
            $literal = $value.Replace("'", "\'")
            $lines.Add("$name='$literal'")
        }
    }
    $directory = Join-Path $Workspace 'runtime-secrets'
    [void](New-Item -ItemType Directory -Path $directory -Force)
    Protect-GeneratedConfigurationPath -Path $directory -Mode '700'
    $path = Join-Path $directory 'core-merchant.env'
    [IO.File]::WriteAllLines($path, $lines, (New-Object Text.UTF8Encoding($false)))
    Protect-GeneratedConfigurationPath -Path $path
    return $path
}

function Get-RedactedConfigurationValue {
    param(
        [string]$Value,
        [bool]$Secret
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return '(not configured)'
    }
    if (-not $Secret) {
        return $Value
    }
    if ($Value.Length -le 4) {
        return '********'
    }
    return "********$($Value.Substring($Value.Length - 4))"
}

function Export-MiniappDeploymentConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$Workspace,
        [Parameter(Mandatory = $true)][hashtable]$Values
    )

    $profiles = [ordered]@{}
    foreach ($pair in @(@('develop', 'DEVELOP'), @('trial', 'TRIAL'), @('release', 'RELEASE'))) {
        $name = $pair[0]
        $prefix = $pair[1]
        $templateIds = @(([string]$Values["MINIAPP_${prefix}_NOTIFICATION_TEMPLATE_IDS"] | ConvertFrom-Json))
        $profiles[$name] = [ordered]@{
            apiBase = [string]$Values["MINIAPP_${prefix}_API_BASE"]
            tenantCode = [string]$Values["MINIAPP_${prefix}_TENANT_CODE"]
            notificationTemplateIds = $templateIds
        }
    }

    $json = $profiles | ConvertTo-Json -Depth 6
    $content = "'use strict'`r`n`r`n// Generated by config-manager.cmd. Do not edit or commit.`r`nmodule.exports = $json`r`n"
    $path = Join-Path $Workspace 'apps/miniapp/src/deployment.config.js'
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $utf8WithoutBom)
    $privateConfiguration = [ordered]@{
        description = 'Generated by config-manager.cmd. Do not edit or commit.'
        projectname = 'smart-charging-wechat'
        appid = [string]$Values['WECHAT_APP_ID']
        setting = [ordered]@{ urlCheck = $true }
    } | ConvertTo-Json -Depth 4
    $privatePath = Join-Path $Workspace 'apps/miniapp/project.private.config.json'
    [System.IO.File]::WriteAllText($privatePath, $privateConfiguration + "`r`n", $utf8WithoutBom)
    return $path
}

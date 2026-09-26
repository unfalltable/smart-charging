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
    New-ConfigurationDefinition 'POSTGRES_PASSWORD' 'Core secrets' $true $true '' 'Hex64' 'PostgreSQL administrator password'
    New-ConfigurationDefinition 'VALKEY_PASSWORD' 'Core secrets' $true $true '' 'Hex64' 'Valkey access password'
    New-ConfigurationDefinition 'QR_SIGNING_SECRET' 'Core secrets' $true $true '' 'Hex64' 'Charging QR-code signing secret'
    New-ConfigurationDefinition 'DEVICE_CREDENTIAL_MASTER_KEY_BASE64' 'Core secrets' $true $true '' 'Base64_32' 'Device credential AES-256 master key'
    New-ConfigurationDefinition 'AUTH_JWT_SECRET_BASE64' 'Core secrets' $true $true '' 'Base64_32' 'Platform access-token signing key'

    New-ConfigurationDefinition 'IDENTITY_PROVIDER_MODE' 'Identity' $true $false 'bundled' '' 'Identity provider mode: bundled or external'
    New-ConfigurationDefinition 'APP_JWT_ISSUER' 'Identity' $true $false 'http://127.0.0.1:8088' '' 'Issuer for platform-issued tokens'
    New-ConfigurationDefinition 'OIDC_ISSUER_URI' 'Identity' $true $false '' '' 'External OIDC issuer URI'
    New-ConfigurationDefinition 'OIDC_JWK_SET_URI' 'Identity' $false $false '' '' 'Optional internal OIDC JSON Web Key Set URI'
    New-ConfigurationDefinition 'API_JWT_AUDIENCE' 'Identity' $true $false 'smart-charging-api' '' 'API JWT audience'
    New-ConfigurationDefinition 'ALLOWED_ORIGINS' 'Identity' $true $false 'http://127.0.0.1:8088,http://localhost:8088' '' 'Comma-separated admin web origins'
    New-ConfigurationDefinition 'VITE_OIDC_AUTHORIZATION_ENDPOINT' 'Identity' $true $false '' '' 'OIDC authorization endpoint'
    New-ConfigurationDefinition 'VITE_OIDC_TOKEN_ENDPOINT' 'Identity' $true $false '' '' 'OIDC token endpoint'
    New-ConfigurationDefinition 'VITE_OIDC_CLIENT_ID' 'Identity' $true $false '' '' 'Admin web OIDC public client ID'
    New-ConfigurationDefinition 'VITE_OIDC_REDIRECT_URI' 'Identity' $true $false 'http://127.0.0.1:8088/auth/callback' '' 'Admin web login callback URI'
    New-ConfigurationDefinition 'VITE_OIDC_SCOPES' 'Identity' $true $false 'openid' '' 'OIDC scopes requested by the admin web'

    New-ConfigurationDefinition 'KEYCLOAK_IMAGE' 'Bundled identity' $true $false 'quay.io/keycloak/keycloak:26.7.4' '' 'Pinned official Keycloak container image'
    New-ConfigurationDefinition 'KEYCLOAK_PORT' 'Bundled identity' $true $false '19090' '' 'Bundled Keycloak HTTP port on loopback'
    New-ConfigurationDefinition 'KEYCLOAK_REALM' 'Bundled identity' $true $false 'smart-charging' '' 'Bundled Keycloak realm'
    New-ConfigurationDefinition 'KEYCLOAK_CLIENT_ID' 'Bundled identity' $true $false 'smart-charging-admin' '' 'Bundled admin-web public client ID'
    New-ConfigurationDefinition 'KEYCLOAK_PROVISIONING_CLIENT_ID' 'Bundled identity' $true $false 'smart-charging-provisioner' '' 'Tenant provisioning service client ID'
    New-ConfigurationDefinition 'KEYCLOAK_PROVISIONING_CLIENT_SECRET' 'Bundled identity' $true $true '' 'Hex64' 'Tenant provisioning service client secret'
    New-ConfigurationDefinition 'KEYCLOAK_DB_PASSWORD' 'Bundled identity' $true $true '' 'Hex64' 'Bundled Keycloak database password'
    New-ConfigurationDefinition 'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME' 'Bundled identity' $true $false '' 'KeycloakAdminUsername' 'Keycloak bootstrap administration username'
    New-ConfigurationDefinition 'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD' 'Bundled identity' $true $true '' 'Hex64' 'Keycloak bootstrap administration password'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_USERNAME' 'Bundled identity' $true $false '' 'PlatformAdminUsername' 'Initial platform administrator username'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_DISPLAY_NAME' 'Bundled identity' $true $false 'Platform Administrator' '' 'Initial platform administrator display name'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_SUBJECT' 'Bundled identity' $true $false '' 'Uuid' 'Stable initial platform administrator subject'
    New-ConfigurationDefinition 'PLATFORM_ADMIN_PASSWORD' 'Bundled identity' $true $true '' 'Hex64' 'Initial platform administrator password'
    New-ConfigurationDefinition 'INITIAL_TENANT_ID' 'Bundled identity' $true $false '' 'Uuid' 'Reserved identifier for the first real tenant'
    New-ConfigurationDefinition 'KEYCLOAK_IMPORT_DIRECTORY' 'Bundled identity' $true $false 'runtime-secrets/keycloak' '' 'Generated Keycloak realm import directory'

    New-ConfigurationDefinition 'WECHAT_IDENTITY_ENABLED' 'WeChat identity' $true $false 'false' '' 'Enable WeChat mini-program login'
    New-ConfigurationDefinition 'WECHAT_APP_ID' 'WeChat identity' $false $false '' '' 'WeChat mini-program AppID'
    New-ConfigurationDefinition 'WECHAT_APP_SECRET' 'WeChat identity' $false $true '' '' 'WeChat mini-program AppSecret'
    New-ConfigurationDefinition 'WECHAT_TENANT_CODE' 'WeChat identity' $false $false '' '' 'Default tenant code for WeChat users'
    New-ConfigurationDefinition 'WECHAT_NOTIFICATION_ENABLED' 'WeChat notification' $true $false 'false' '' 'Enable WeChat subscription-message delivery'
    New-ConfigurationDefinition 'WECHAT_NOTIFICATION_TEMPLATES_JSON' 'WeChat notification' $false $false '{}' '' 'Server notification-type to WeChat-template JSON mapping'

    New-ConfigurationDefinition 'WECHAT_PAYMENT_ENABLED' 'WeChat payment' $true $false 'false' '' 'Enable WeChat Pay key material'
    New-ConfigurationDefinition 'WECHAT_PAYMENT_DIRECTORY' 'WeChat payment' $true $false 'runtime-secrets/wechat-pay' '' 'WeChat Pay key-material directory'
    New-ConfigurationDefinition 'WECHAT_PRIMARY_MERCHANT_SERIAL' 'WeChat payment' $false $false '' '' 'Merchant API certificate serial number'
    New-ConfigurationDefinition 'WECHAT_PRIMARY_API_V3_KEY' 'WeChat payment' $false $true '' '' 'WeChat Pay APIv3 key'
    New-ConfigurationDefinition 'WECHAT_PRIMARY_PUBLIC_KEY_ID' 'WeChat payment' $false $false '' '' 'WeChat Pay public-key ID'

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
    return Join-Path $Workspace '.env.docker'
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
        'Uuid' {
            return [guid]::NewGuid().ToString()
        }
        'KeycloakAdminUsername' {
            $suffix = -join ((New-RandomBytes -Length 4) | ForEach-Object { $_.ToString('x2') })
            return "keycloak-admin-$suffix"
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
        $lines.Add("$($definition.Name)=$value")
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
            $lines.Add("$name=$value")
        }
    }

    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $directory -Force)
    }
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $lines, $utf8WithoutBom)
}

function Set-BundledIdentityConfiguration {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    if ([string]$Values['IDENTITY_PROVIDER_MODE'] -ne 'bundled') {
        return $false
    }

    $identityBase = "http://127.0.0.1:$([string]$Values['KEYCLOAK_PORT'])"
    $issuer = "$identityBase/realms/$([string]$Values['KEYCLOAK_REALM'])"
    $internalIssuer = "http://keycloak:$([string]$Values['KEYCLOAK_PORT'])/realms/$([string]$Values['KEYCLOAK_REALM'])"
    $adminPort = [string]$Values['ADMIN_WEB_PORT']
    $derived = [ordered]@{
        APP_JWT_ISSUER = "http://127.0.0.1:$adminPort"
        OIDC_ISSUER_URI = $issuer
        OIDC_JWK_SET_URI = "$internalIssuer/protocol/openid-connect/certs"
        ALLOWED_ORIGINS = "http://127.0.0.1:$adminPort,http://localhost:$adminPort"
        VITE_OIDC_AUTHORIZATION_ENDPOINT = "$issuer/protocol/openid-connect/auth"
        VITE_OIDC_TOKEN_ENDPOINT = "$issuer/protocol/openid-connect/token"
        VITE_OIDC_CLIENT_ID = [string]$Values['KEYCLOAK_CLIENT_ID']
        VITE_OIDC_REDIRECT_URI = "http://127.0.0.1:$adminPort/auth/callback"
        VITE_OIDC_SCOPES = 'openid'
    }
    $changed = $false
    foreach ($entry in $derived.GetEnumerator()) {
        if ([string]$Values[$entry.Key] -ne $entry.Value) {
            $Values[$entry.Key] = $entry.Value
            $changed = $true
        }
    }
    return $changed
}

function Initialize-DeploymentConfiguration {
    param([Parameter(Mandatory = $true)][string]$Workspace)

    $path = Get-DeploymentConfigurationPath -Workspace $Workspace
    $created = -not (Test-Path -LiteralPath $path -PathType Leaf)
    $values = Read-DeploymentConfiguration -Path $path
    $hadIdentityMode = $values.ContainsKey('IDENTITY_PROVIDER_MODE')
    $hadExternalIssuer = $values.ContainsKey('OIDC_ISSUER_URI') -and
        -not [string]::IsNullOrWhiteSpace([string]$values['OIDC_ISSUER_URI'])
    if ($values.ContainsKey('PILOT_DEVICE_SECRET')) {
        throw 'Legacy pilot/demo configuration detected. Delete .env.docker and run config-manager.cmd init to create a clean deployment configuration.'
    }

    $changed = $created
    foreach ($definition in $script:DeploymentConfigurationSchema) {
        if (-not $values.ContainsKey($definition.Name)) {
            $values[$definition.Name] = New-GeneratedConfigurationValue -Definition $definition
            $changed = $true
        }
    }

    if (-not $hadIdentityMode -and $hadExternalIssuer) {
        $values['IDENTITY_PROVIDER_MODE'] = 'external'
        $changed = $true
    }
    if (Set-BundledIdentityConfiguration -Values $values) {
        $changed = $true
    }

    if ($changed) {
        Write-DeploymentConfiguration -Path $path -Values $values
    }

    return [pscustomobject]@{
        Path = $path
        Values = $values
        Created = $created
        Changed = $changed
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

    if (@('bundled', 'external') -notcontains [string]$Values['IDENTITY_PROVIDER_MODE']) {
        $errors.Add('IDENTITY_PROVIDER_MODE: value must be bundled or external')
    }

    foreach ($name in @('WECHAT_IDENTITY_ENABLED', 'WECHAT_NOTIFICATION_ENABLED', 'WECHAT_PAYMENT_ENABLED', 'DEVICE_GATEWAY_ENABLED')) {
        if ($Values.ContainsKey($name) -and @('true', 'false') -notcontains [string]$Values[$name]) {
            $errors.Add("$($name): value must be true or false")
        }
    }

    foreach ($name in @('APP_JWT_ISSUER', 'OIDC_ISSUER_URI', 'VITE_OIDC_AUTHORIZATION_ENDPOINT', 'VITE_OIDC_TOKEN_ENDPOINT', 'VITE_OIDC_REDIRECT_URI')) {
        if ($Values.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$name]) -and -not (Test-AbsoluteSecureUrl -Value ([string]$Values[$name]))) {
            $errors.Add("$($name): use an absolute HTTPS URL; HTTP is allowed only for loopback development")
        }
    }
    if ([string]$Values['IDENTITY_PROVIDER_MODE'] -eq 'external' -and
            -not [string]::IsNullOrWhiteSpace([string]$Values['OIDC_JWK_SET_URI']) -and
            -not (Test-AbsoluteHttpsUrl -Value ([string]$Values['OIDC_JWK_SET_URI']))) {
        $errors.Add('OIDC_JWK_SET_URI: external key-set URI must use HTTPS')
    }

    if ($Values.ContainsKey('ALLOWED_ORIGINS')) {
        foreach ($origin in ([string]$Values['ALLOWED_ORIGINS']).Split(',')) {
            if (-not (Test-AbsoluteSecureUrl -Value $origin.Trim())) {
                $errors.Add('ALLOWED_ORIGINS: every origin must be an absolute HTTPS URL; HTTP is allowed only for loopback development')
                break
            }
        }
    }

    foreach ($name in @('POSTGRES_PASSWORD', 'VALKEY_PASSWORD', 'QR_SIGNING_SECRET', 'KEYCLOAK_DB_PASSWORD',
            'KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD', 'KEYCLOAK_PROVISIONING_CLIENT_SECRET', 'PLATFORM_ADMIN_PASSWORD')) {
        if ($Values.ContainsKey($name) -and ([string]$Values[$name]).Length -lt 32) {
            $errors.Add("$($name): secret must contain at least 32 characters")
        }
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

    $ports = @('ADMIN_WEB_PORT', 'CORE_PORT', 'KEYCLOAK_PORT', 'DEVICE_GATEWAY_PORT', 'DEVICE_MANAGEMENT_PORT', 'POSTGRES_PORT', 'VALKEY_PORT', 'NATS_PORT', 'NATS_MONITOR_PORT')
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

    foreach ($name in @('DATABASE_POOL_SIZE', 'DATABASE_POOL_MIN_IDLE', 'ACCESS_TOKEN_MINUTES', 'REFRESH_TOKEN_DAYS', 'RATE_LIMIT_DEFAULT_PER_MINUTE', 'RATE_LIMIT_PUBLIC_PER_MINUTE', 'RATE_LIMIT_LOGIN_PER_MINUTE', 'OUTBOX_PUBLISHER_DELAY_MS')) {
        $number = 0
        if (-not [int]::TryParse([string]$Values[$name], [ref]$number) -or $number -lt 1) {
            $errors.Add("$($name): value must be a positive integer")
        }
    }
    $sampleRate = 0.0
    if (-not [double]::TryParse([string]$Values['TRACING_SAMPLE_RATE'], [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$sampleRate) -or $sampleRate -lt 0 -or $sampleRate -gt 1) {
        $errors.Add('TRACING_SAMPLE_RATE: value must be between 0 and 1')
    }

    if ([string]$Values['IDENTITY_PROVIDER_MODE'] -eq 'bundled') {
        foreach ($name in @('KEYCLOAK_IMAGE', 'KEYCLOAK_REALM', 'KEYCLOAK_CLIENT_ID', 'KEYCLOAK_PROVISIONING_CLIENT_ID',
                'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME', 'PLATFORM_ADMIN_USERNAME', 'PLATFORM_ADMIN_DISPLAY_NAME',
                'PLATFORM_ADMIN_SUBJECT', 'INITIAL_TENANT_ID', 'KEYCLOAK_IMPORT_DIRECTORY')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values[$name])) {
                $errors.Add("$($name): required when IDENTITY_PROVIDER_MODE=bundled")
            }
        }
        foreach ($name in @('KEYCLOAK_REALM', 'KEYCLOAK_CLIENT_ID', 'KEYCLOAK_PROVISIONING_CLIENT_ID',
                'KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME', 'PLATFORM_ADMIN_USERNAME')) {
            if ([string]$Values[$name] -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$') {
                $errors.Add("$($name): use 3 to 64 letters, digits, dots, underscores or hyphens")
            }
        }
        if ([string]$Values['KEYCLOAK_IMAGE'] -match ':(latest|edge)$') {
            $errors.Add('KEYCLOAK_IMAGE: use a pinned version, not latest or edge')
        }
        foreach ($name in @('PLATFORM_ADMIN_SUBJECT', 'INITIAL_TENANT_ID')) {
            $identifier = [guid]::Empty
            if (-not [guid]::TryParse([string]$Values[$name], [ref]$identifier) -or $identifier -eq [guid]::Empty) {
                $errors.Add("$($name): value must be a non-zero UUID")
            }
        }
    }

    if (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_IDENTITY_ENABLED') {
        foreach ($name in @('WECHAT_APP_ID', 'WECHAT_APP_SECRET', 'WECHAT_TENANT_CODE')) {
            if ([string]::IsNullOrWhiteSpace([string]$Values[$name])) {
                $errors.Add("$($name): required when WECHAT_IDENTITY_ENABLED=true")
            }
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

    foreach ($environment in @('DEVELOP', 'TRIAL', 'RELEASE')) {
        $apiName = "MINIAPP_${environment}_API_BASE"
        $tenantName = "MINIAPP_${environment}_TENANT_CODE"
        $templatesName = "MINIAPP_${environment}_NOTIFICATION_TEMPLATE_IDS"
        $apiValue = [string]$Values[$apiName]
        $tenantValue = [string]$Values[$tenantName]
        if (-not [string]::IsNullOrWhiteSpace($apiValue) -or -not [string]::IsNullOrWhiteSpace($tenantValue)) {
            if ([string]::IsNullOrWhiteSpace($apiValue) -or -not (Test-AbsoluteHttpsUrl -Value $apiValue)) {
                $errors.Add("$($apiName): a valid HTTPS API URL is required for a configured mini program profile")
            }
            if ([string]::IsNullOrWhiteSpace($tenantValue)) {
                $errors.Add("$($tenantName): tenant code is required for a configured mini program profile")
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
    return $path
}

function Export-BundledIdentityConfiguration {
    param(
        [Parameter(Mandatory = $true)][string]$Workspace,
        [Parameter(Mandatory = $true)][hashtable]$Values
    )

    if ([string]$Values['IDENTITY_PROVIDER_MODE'] -ne 'bundled') {
        return ''
    }

    $roleNames = @('internal', 'admin', 'operator', 'finance', 'auditor', 'support')
    $realmRoles = @($roleNames | ForEach-Object {
        [ordered]@{ name = $_; description = "Smart Charging $($_) authority" }
    })
    $allowedOrigins = @(([string]$Values['ALLOWED_ORIGINS']).Split(',') |
        ForEach-Object { $_.Trim().TrimEnd('/') } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $redirectUris = @($allowedOrigins | ForEach-Object { "$_/*" })
    $configuredRedirect = [string]$Values['VITE_OIDC_REDIRECT_URI']
    if (-not [string]::IsNullOrWhiteSpace($configuredRedirect) -and $redirectUris -notcontains $configuredRedirect) {
        $redirectUris += $configuredRedirect
    }

    $platformScope = [ordered]@{
        name = 'smart-charging-api'
        description = 'Smart Charging API audience'
        protocol = 'openid-connect'
        attributes = [ordered]@{ 'include.in.token.scope' = 'false'; 'display.on.consent.screen' = 'false' }
        protocolMappers = @(
            [ordered]@{
                name = 'platform-api-audience'
                protocol = 'openid-connect'
                protocolMapper = 'oidc-audience-mapper'
                consentRequired = $false
                config = [ordered]@{
                    'included.custom.audience' = [string]$Values['API_JWT_AUDIENCE']
                    'id.token.claim' = 'false'
                    'access.token.claim' = 'true'
                    'introspection.token.claim' = 'true'
                }
            },
            [ordered]@{
                name = 'platform-tenant-ids'
                protocol = 'openid-connect'
                protocolMapper = 'oidc-usermodel-attribute-mapper'
                consentRequired = $false
                config = [ordered]@{
                    'user.attribute' = 'tenant_ids'
                    'claim.name' = 'tenant_ids'
                    'jsonType.label' = 'String'
                    'multivalued' = 'true'
                    'id.token.claim' = 'false'
                    'access.token.claim' = 'true'
                    'userinfo.token.claim' = 'false'
                }
            },
            [ordered]@{
                name = 'platform-realm-roles'
                protocol = 'openid-connect'
                protocolMapper = 'oidc-usermodel-realm-role-mapper'
                consentRequired = $false
                config = [ordered]@{
                    'claim.name' = 'realm_access.roles'
                    'jsonType.label' = 'String'
                    'multivalued' = 'true'
                    'id.token.claim' = 'false'
                    'access.token.claim' = 'true'
                    'userinfo.token.claim' = 'false'
                    'introspection.token.claim' = 'true'
                    'usermodel.realmRoleMapping.rolePrefix' = ''
                }
            }
        )
    }

    $realm = [ordered]@{
        realm = [string]$Values['KEYCLOAK_REALM']
        displayName = 'Smart Charging Identity'
        enabled = $true
        sslRequired = 'none'
        registrationAllowed = $false
        registrationEmailAsUsername = $false
        rememberMe = $false
        verifyEmail = $false
        loginWithEmailAllowed = $false
        duplicateEmailsAllowed = $false
        resetPasswordAllowed = $true
        editUsernameAllowed = $false
        bruteForceProtected = $true
        permanentLockout = $false
        maxFailureWaitSeconds = 900
        minimumQuickLoginWaitSeconds = 60
        waitIncrementSeconds = 60
        quickLoginCheckMilliSeconds = 1000
        maxDeltaTimeSeconds = 43200
        failureFactor = 5
        accessTokenLifespan = 900
        ssoSessionIdleTimeout = 1800
        ssoSessionMaxLifespan = 36000
        roles = [ordered]@{ realm = $realmRoles }
        clientScopes = @($platformScope)
        clients = @(
            [ordered]@{
                clientId = [string]$Values['KEYCLOAK_CLIENT_ID']
                name = 'Smart Charging Admin Web'
                enabled = $true
                protocol = 'openid-connect'
                publicClient = $true
                fullScopeAllowed = $true
                bearerOnly = $false
                standardFlowEnabled = $true
                implicitFlowEnabled = $false
                directAccessGrantsEnabled = $false
                serviceAccountsEnabled = $false
                frontchannelLogout = $true
                redirectUris = $redirectUris
                webOrigins = $allowedOrigins
                attributes = [ordered]@{
                    'pkce.code.challenge.method' = 'S256'
                    'post.logout.redirect.uris' = '+'
                }
                defaultClientScopes = @('web-origins', 'acr', 'profile', 'roles', 'email', 'smart-charging-api')
                optionalClientScopes = @('address', 'phone', 'offline_access', 'microprofile-jwt')
            },
            [ordered]@{
                clientId = [string]$Values['KEYCLOAK_PROVISIONING_CLIENT_ID']
                name = 'Smart Charging Tenant Provisioner'
                enabled = $true
                protocol = 'openid-connect'
                publicClient = $false
                clientAuthenticatorType = 'client-secret'
                secret = [string]$Values['KEYCLOAK_PROVISIONING_CLIENT_SECRET']
                bearerOnly = $false
                fullScopeAllowed = $true
                standardFlowEnabled = $false
                implicitFlowEnabled = $false
                directAccessGrantsEnabled = $false
                serviceAccountsEnabled = $true
                defaultClientScopes = @('roles', 'smart-charging-api')
            }
        )
        users = @(
            [ordered]@{
                id = [string]$Values['PLATFORM_ADMIN_SUBJECT']
                username = [string]$Values['PLATFORM_ADMIN_USERNAME']
                enabled = $true
                emailVerified = $true
                firstName = [string]$Values['PLATFORM_ADMIN_DISPLAY_NAME']
                attributes = [ordered]@{ tenant_ids = @([string]$Values['INITIAL_TENANT_ID']) }
                requiredActions = @('UPDATE_PASSWORD')
                credentials = @(
                    [ordered]@{
                        type = 'password'
                        value = [string]$Values['PLATFORM_ADMIN_PASSWORD']
                        temporary = $true
                    }
                )
                realmRoles = $roleNames
            },
            [ordered]@{
                username = "service-account-$([string]$Values['KEYCLOAK_PROVISIONING_CLIENT_ID'])"
                enabled = $true
                serviceAccountClientId = [string]$Values['KEYCLOAK_PROVISIONING_CLIENT_ID']
                realmRoles = @('internal')
            }
        )
    }

    $directory = Resolve-ConfigurationDirectory -Workspace $Workspace -ConfiguredPath ([string]$Values['KEYCLOAK_IMPORT_DIRECTORY'])
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $directory -Force)
    }
    $path = Join-Path $directory 'smart-charging-realm.json'
    $content = $realm | ConvertTo-Json -Depth 12
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $utf8WithoutBom)
    return $path
}

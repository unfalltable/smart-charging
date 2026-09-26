param(
    [ValidateSet('init', 'status', 'validate', 'wizard', 'export-miniapp')]
    [string]$Command = 'status'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')

function Read-SecretText {
    param([string]$Prompt)

    $secureValue = Read-Host -Prompt $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Set-ConfigurationValueInteractively {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $definition = Get-DeploymentConfigurationSchema | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
    if ($null -eq $definition) {
        throw "Unknown configuration key: $Name"
    }

    $current = [string]$Values[$Name]
    if ($definition.Secret) {
        $state = if ([string]::IsNullOrWhiteSpace($current)) { 'not configured' } else { 'configured; Enter keeps it; !clear removes it' }
        $newValue = Read-SecretText -Prompt "$($definition.Description) [$state]"
    }
    else {
        $shown = if ([string]::IsNullOrWhiteSpace($current)) { 'not configured' } else { $current }
        $newValue = Read-Host -Prompt "$($definition.Description) [$shown; Enter keeps it; !clear removes it]"
    }

    if ($newValue -eq '!clear') {
        $Values[$Name] = ''
    }
    elseif (-not [string]::IsNullOrWhiteSpace($newValue)) {
        $Values[$Name] = $newValue.Trim()
    }
}

function Read-YesNo {
    param(
        [string]$Prompt,
        [bool]$CurrentValue
    )

    $defaultText = if ($CurrentValue) { 'Y' } else { 'N' }
    while ($true) {
        $answer = (Read-Host -Prompt "$Prompt [y/n; default $defaultText]").Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($answer)) {
            return $CurrentValue
        }
        if (@('y', 'yes') -contains $answer) {
            return $true
        }
        if (@('n', 'no') -contains $answer) {
            return $false
        }
        Write-Host 'Enter y or n.' -ForegroundColor Yellow
    }
}

function Invoke-ConfigurationWizard {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    Write-Host ''
    Write-Host 'Existing secrets are never displayed. Press Enter to preserve a value.' -ForegroundColor Cyan
    Write-Host '1. Identity (a real OIDC provider is required)' -ForegroundColor Cyan
    foreach ($name in @('APP_JWT_ISSUER', 'OIDC_ISSUER_URI', 'API_JWT_AUDIENCE', 'ALLOWED_ORIGINS', 'VITE_OIDC_AUTHORIZATION_ENDPOINT', 'VITE_OIDC_TOKEN_ENDPOINT', 'VITE_OIDC_CLIENT_ID', 'VITE_OIDC_REDIRECT_URI', 'VITE_OIDC_SCOPES')) {
        Set-ConfigurationValueInteractively -Values $Values -Name $name
    }

    Write-Host ''
    Write-Host '2. WeChat identity and notifications' -ForegroundColor Cyan
    $wechatIdentity = Read-YesNo -Prompt 'Enable WeChat mini-program login' -CurrentValue (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_IDENTITY_ENABLED')
    $Values['WECHAT_IDENTITY_ENABLED'] = $wechatIdentity.ToString().ToLowerInvariant()
    if ($wechatIdentity) {
        foreach ($name in @('WECHAT_APP_ID', 'WECHAT_APP_SECRET', 'WECHAT_TENANT_CODE')) {
            Set-ConfigurationValueInteractively -Values $Values -Name $name
        }
        $wechatNotification = Read-YesNo -Prompt 'Enable WeChat subscription messages' -CurrentValue (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_NOTIFICATION_ENABLED')
        $Values['WECHAT_NOTIFICATION_ENABLED'] = $wechatNotification.ToString().ToLowerInvariant()
        if ($wechatNotification) {
            Set-ConfigurationValueInteractively -Values $Values -Name 'WECHAT_NOTIFICATION_TEMPLATES_JSON'
        }
    }
    else {
        $Values['WECHAT_NOTIFICATION_ENABLED'] = 'false'
    }

    Write-Host ''
    Write-Host '3. WeChat Pay key material' -ForegroundColor Cyan
    $wechatPayment = Read-YesNo -Prompt 'Configure and enable WeChat Pay material' -CurrentValue (Get-ConfigurationBoolean -Values $Values -Name 'WECHAT_PAYMENT_ENABLED')
    $Values['WECHAT_PAYMENT_ENABLED'] = $wechatPayment.ToString().ToLowerInvariant()
    if ($wechatPayment) {
        foreach ($name in @('WECHAT_PAYMENT_DIRECTORY', 'WECHAT_PRIMARY_MERCHANT_SERIAL', 'WECHAT_PRIMARY_API_V3_KEY', 'WECHAT_PRIMARY_PUBLIC_KEY_ID')) {
            Set-ConfigurationValueInteractively -Values $Values -Name $name
        }
        Write-Host 'Place merchant-private-key.pem and wechat-pay-public-key.pem in that directory.' -ForegroundColor Yellow
    }

    Write-Host ''
    Write-Host '4. Device gateway' -ForegroundColor Cyan
    $deviceGateway = Read-YesNo -Prompt 'Start the real device gateway' -CurrentValue (Get-ConfigurationBoolean -Values $Values -Name 'DEVICE_GATEWAY_ENABLED')
    $Values['DEVICE_GATEWAY_ENABLED'] = $deviceGateway.ToString().ToLowerInvariant()
    if ($deviceGateway) {
        foreach ($name in @('DEVICE_GATEWAY_BIND_ADDRESS', 'DEVICE_TLS_DIRECTORY')) {
            Set-ConfigurationValueInteractively -Values $Values -Name $name
        }
        Write-Host 'The certificate directory must contain tls.crt, tls.key and ca.crt.' -ForegroundColor Yellow
    }

    Write-Host ''
    Write-Host '5. Mini-program environments' -ForegroundColor Cyan
    foreach ($pair in @(@('develop', 'DEVELOP'), @('trial', 'TRIAL'), @('release', 'RELEASE'))) {
        $configureProfile = Read-YesNo -Prompt "Configure mini-program $($pair[0]) profile" -CurrentValue (-not [string]::IsNullOrWhiteSpace([string]$Values["MINIAPP_$($pair[1])_API_BASE"]))
        if ($configureProfile) {
            foreach ($suffix in @('API_BASE', 'TENANT_CODE', 'NOTIFICATION_TEMPLATE_IDS')) {
                Set-ConfigurationValueInteractively -Values $Values -Name "MINIAPP_$($pair[1])_$suffix"
            }
        }
    }

    Write-DeploymentConfiguration -Path (Get-DeploymentConfigurationPath -Workspace $workspace) -Values $Values
    $miniappPath = Export-MiniappDeploymentConfiguration -Workspace $workspace -Values $Values
    Write-Host ''
    Write-Host "Configuration saved. Mini-program configuration generated at: $miniappPath" -ForegroundColor Green
}

function Show-ConfigurationStatus {
    param([Parameter(Mandatory = $true)][hashtable]$Values)

    $lastCategory = ''
    foreach ($definition in Get-DeploymentConfigurationSchema) {
        if ($definition.Category -ne $lastCategory) {
            Write-Host ''
            Write-Host "[$($definition.Category)]" -ForegroundColor Cyan
            $lastCategory = $definition.Category
        }
        $value = [string]$Values[$definition.Name]
        $status = if ([string]::IsNullOrWhiteSpace($value)) { 'MISSING' } else { 'SET' }
        $color = if ([string]::IsNullOrWhiteSpace($value) -and $definition.Required) { 'Red' } elseif ([string]::IsNullOrWhiteSpace($value)) { 'DarkGray' } else { 'Green' }
        $shown = Get-RedactedConfigurationValue -Value $value -Secret $definition.Secret
        Write-Host ("{0,-52} {1,-8} {2}" -f $definition.Name, $status, $shown) -ForegroundColor $color
    }
}

try {
    $state = Initialize-DeploymentConfiguration -Workspace $workspace
    $values = $state.Values

    switch ($Command) {
        'init' {
            $miniappPath = Export-MiniappDeploymentConfiguration -Workspace $workspace -Values $values
            if ($state.Created) {
                Write-Host "Created unified configuration: $($state.Path)" -ForegroundColor Green
            }
            elseif ($state.Changed) {
                Write-Host "Added new fields to unified configuration: $($state.Path)" -ForegroundColor Green
            }
            else {
                Write-Host "Unified configuration already exists: $($state.Path)" -ForegroundColor Green
            }
            Write-Host "Mini-program deployment configuration: $miniappPath"
            Write-Host 'Next, run .\config-manager.cmd wizard and enter real external configuration.'
        }
        'status' {
            Write-Host "Configuration file: $($state.Path)"
            Show-ConfigurationStatus -Values $values
            Write-Host ''
            Write-Host 'Secrets are always redacted. Tenant, merchant-channel, station, tariff and device binding data is managed in the database.' -ForegroundColor DarkGray
        }
        'validate' {
            $errors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $values)
            if ($errors.Count -gt 0) {
                Write-Host 'Configuration validation failed:' -ForegroundColor Red
                foreach ($errorMessage in $errors) {
                    Write-Host " - $errorMessage" -ForegroundColor Red
                }
                exit 1
            }
            Write-Host 'Configuration validation passed.' -ForegroundColor Green
        }
        'wizard' {
            Invoke-ConfigurationWizard -Values $values
            $errors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $values)
            if ($errors.Count -gt 0) {
                Write-Host 'Configuration was saved, but these issues remain:' -ForegroundColor Yellow
                foreach ($errorMessage in $errors) {
                    Write-Host " - $errorMessage" -ForegroundColor Yellow
                }
                exit 1
            }
            Write-Host 'Configuration validation passed.' -ForegroundColor Green
        }
        'export-miniapp' {
            $miniappPath = Export-MiniappDeploymentConfiguration -Workspace $workspace -Values $values
            Write-Host "Generated mini-program deployment configuration: $miniappPath" -ForegroundColor Green
        }
    }
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

param(
    [ValidateSet('init', 'status', 'validate', 'export-miniapp', 'credentials')]
    [string]$Command = 'status'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')

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
        $color = if ([string]::IsNullOrWhiteSpace($value) -and $definition.Required) {
            'Red'
        }
        elseif ([string]::IsNullOrWhiteSpace($value)) {
            'DarkGray'
        }
        else {
            'Green'
        }
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
            if ($state.Migrated) {
                Write-Host "Existing configuration migrated to: $($state.Path)" -ForegroundColor Green
            }
            elseif ($state.Created) {
                Write-Host "Created editable environment file: $($state.Path)" -ForegroundColor Green
            }
            elseif ($state.Changed) {
                Write-Host "Added new fields or generated missing internal secrets in: $($state.Path)" -ForegroundColor Green
            }
            else {
                Write-Host "Environment file already exists: $($state.Path)" -ForegroundColor Green
            }
            Write-Host "Mini-program deployment configuration: $miniappPath"
            Write-Host 'Edit .env directly, then run .\config-manager.cmd validate and .\docker-start.cmd.' -ForegroundColor Cyan
        }
        'status' {
            Write-Host "Environment file: $($state.Path)"
            Show-ConfigurationStatus -Values $values
            Write-Host ''
            Write-Host 'Secrets are redacted. Edit the root .env file directly.' -ForegroundColor DarkGray
        }
        'validate' {
            $errors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $values)
            if ($errors.Count -gt 0) {
                Write-Host 'Environment validation failed:' -ForegroundColor Red
                foreach ($errorMessage in $errors) {
                    Write-Host " - $errorMessage" -ForegroundColor Red
                }
                exit 1
            }
            Write-Host 'Environment validation passed.' -ForegroundColor Green
        }
        'export-miniapp' {
            $errors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $values)
            if ($errors.Count -gt 0) {
                throw "Cannot export invalid .env configuration:`r`n - $($errors -join "`r`n - ")"
            }
            $miniappPath = Export-MiniappDeploymentConfiguration -Workspace $workspace -Values $values
            Write-Host "Generated mini-program deployment configuration: $miniappPath" -ForegroundColor Green
        }
        'credentials' {
            Write-Host 'Sensitive credentials are shown because the credentials command was explicitly requested.' -ForegroundColor Yellow
            Write-Host "Platform login URL: http://127.0.0.1:$([string]$values['ADMIN_WEB_PORT'])/"
            Write-Host "Platform username: $([string]$values['PLATFORM_ADMIN_USERNAME'])"
            Write-Host "Platform subject: $([string]$values['PLATFORM_ADMIN_SUBJECT'])"
            Write-Host "Platform temporary password: $([string]$values['PLATFORM_ADMIN_PASSWORD'])"
            Write-Host 'This password works only until the first successful change; startup never overwrites the changed database password.' -ForegroundColor Yellow
        }
    }
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

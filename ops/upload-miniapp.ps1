param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Version,
    [Parameter(Position = 1)][string]$Description = ''
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'configuration.ps1')

try {
    $state = Initialize-DeploymentConfiguration -Workspace $workspace
    $values = $state.Values
    $errors = @(Test-DeploymentConfiguration -Workspace $workspace -Values $values)
    if ($errors.Count -gt 0) {
        throw "Deployment configuration is invalid:`r`n - $($errors -join "`r`n - ")"
    }
    foreach ($name in @('WECHAT_APP_ID', 'WECHAT_APP_SECRET', 'WECHAT_TENANT_CODE',
            'MINIAPP_RELEASE_API_BASE', 'MINIAPP_RELEASE_TENANT_CODE')) {
        if ([string]::IsNullOrWhiteSpace([string]$values[$name])) {
            throw "$name is required to upload the production mini-program."
        }
    }
    if (-not (Get-ConfigurationBoolean -Values $values -Name 'WECHAT_IDENTITY_ENABLED')) {
        throw 'WECHAT_IDENTITY_ENABLED must be true to upload the production mini-program.'
    }
    [void](Export-MiniappDeploymentConfiguration -Workspace $workspace -Values $values)
    $configuredCli = [string]$values['MINIAPP_DEVTOOLS_CLI_PATH']
    $candidates = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($configuredCli)) {
        $candidates.Add($configuredCli)
    }
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
        $candidates.Add((Join-Path ${env:ProgramFiles(x86)} 'Tencent\微信web开发者工具\cli.bat'))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates.Add((Join-Path $env:ProgramFiles 'Tencent\微信开发者工具\cli.bat'))
        $candidates.Add((Join-Path $env:ProgramFiles 'Tencent\微信web开发者工具\cli.bat'))
    }
    $cliPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($cliPath)) {
        throw 'WeChat Developer Tools cli.bat was not found. Install Developer Tools or set MINIAPP_DEVTOOLS_CLI_PATH.'
    }
    if ([string]::IsNullOrWhiteSpace($Description)) { $Description = "Smart Charging $Version" }
    if ($Description.Length -gt 100) { throw 'Description cannot exceed 100 characters.' }
    if ($Version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
        throw 'Version must use semantic format such as 2.0.0.'
    }

    $projectPath = Join-Path $workspace 'apps\miniapp'
    & $cliPath upload --project $projectPath -v $Version -d $Description
    if ($LASTEXITCODE -ne 0) { throw 'Mini-program upload failed.' }
    Write-Host 'Code upload completed. Submit this version for review in the WeChat public platform.' -ForegroundColor Green
}
catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

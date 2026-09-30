Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ($env:GITHUB_ACTIONS -ne 'true') {
    throw 'This test creates transient business records. It is restricted to the isolated GitHub Actions runner.'
}
. (Join-Path $workspace 'ops/configuration.ps1')
$state = Initialize-DeploymentConfiguration -Workspace $workspace
& (Join-Path $workspace 'ops/start-local.ps1') -TimeoutSeconds 300
$port = [string]$state.Values['ADMIN_WEB_PORT']
$base = "http://127.0.0.1:$port/api/v1"
function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [string]$Token = '')
    $parameters = @{ Uri = "$base$Path"; Method = $Method; ContentType = 'application/json'; TimeoutSec = 15 }
    if ($null -ne $Body) { $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress }
    if ($Token) { $parameters.Headers = @{ Authorization = "Bearer $Token" } }
    return Invoke-RestMethod @parameters
}
$initialPassword = [string]$state.Values['PLATFORM_ADMIN_PASSWORD']
$session = Invoke-Api 'POST' '/auth/admin/login' @{ username = [string]$state.Values['PLATFORM_ADMIN_USERNAME']; password = $initialPassword }
if (-not $session.mustChangePassword) { throw 'First platform login did not require a password change.' }
$changedPassword = 'Aa7!' + (-join ((New-RandomBytes -Length 24) | ForEach-Object { $_.ToString('x2') }))
$session = Invoke-Api 'POST' '/auth/admin/password' @{ currentPassword = $initialPassword; newPassword = $changedPassword } $session.accessToken
$current = Invoke-Api 'GET' '/session' $null $session.accessToken
if (-not $current.platformAdministrator -or @($current.tenants).Count -ne 0) { throw 'The initial deployment must have exactly one superadmin and no tenant data.' }
$tenant = Invoke-Api 'POST' '/platform/tenants' @{ code = 'ci-isolated'; displayName = 'Isolated CI' } $session.accessToken
$operator = Invoke-Api 'POST' '/platform/users' @{ tenantId = $tenant.id; username = 'ci-operator'; displayName = 'CI operator'; roleCode = 'OPERATOR' } $session.accessToken
if (-not $operator.temporaryPassword) { throw 'Operator password was not returned once.' }
$operatorSession = Invoke-Api 'POST' '/auth/admin/login' @{ username = 'ci-operator'; password = $operator.temporaryPassword }
$operatorPassword = 'Aa7!' + (-join ((New-RandomBytes -Length 24) | ForEach-Object { $_.ToString('x2') }))
$operatorSession = Invoke-Api 'POST' '/auth/admin/password' @{ currentPassword = $operator.temporaryPassword; newPassword = $operatorPassword } $operatorSession.accessToken
$operatorCurrent = Invoke-Api 'GET' '/session' $null $operatorSession.accessToken
if ($operatorCurrent.platformAdministrator -or @($operatorCurrent.tenants).Count -ne 1) { throw 'Operator tenant access was not assigned correctly.' }
try {
    Invoke-Api 'GET' '/platform/users' $null $operatorSession.accessToken | Out-Null
    throw 'An operator unexpectedly gained platform user-management access.'
}
catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 403) { throw }
}
$compose = @('compose', '--env-file', $state.Path, '--file', (Join-Path $workspace 'ops/compose.local.yaml'))
$runtimeFlags = & docker @compose exec -T postgres psql -U charging_app -d charging -t -A -c "select rolsuper::text || ':' || rolbypassrls::text from pg_roles where rolname='charging_runtime'"
if ($LASTEXITCODE -ne 0 -or $runtimeFlags.Trim() -ne 'false:false') { throw 'The application database role can bypass tenant isolation.' }
$natsClient = New-Object Net.Sockets.TcpClient
try {
    $natsClient.Connect('127.0.0.1', [int]$state.Values['NATS_PORT'])
    $stream = $natsClient.GetStream()
    $stream.ReadTimeout = 3000
    $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::ASCII)
    $greeting = $reader.ReadLine()
    if (-not $greeting.StartsWith('INFO ') -or -not (($greeting.Substring(5) | ConvertFrom-Json).auth_required)) {
        throw 'NATS is accepting connections without authentication.'
    }
    $unauthenticatedConnect = [Text.Encoding]::ASCII.GetBytes("CONNECT {}`r`nPING`r`n")
    $stream.Write($unauthenticatedConnect, 0, $unauthenticatedConnect.Length)
    $rejection = $reader.ReadLine()
    if ($rejection -notmatch 'Authorization Violation') { throw 'NATS did not reject an unauthenticated CONNECT.' }
}
finally { $natsClient.Dispose() }
& (Join-Path $workspace 'ops/backup.ps1')
$backupRoot = Resolve-ConfigurationDirectory -Workspace $workspace -ConfiguredPath ([string]$state.Values['BACKUP_DIRECTORY'])
$snapshot = Get-ChildItem -LiteralPath $backupRoot -Directory -Filter 'snapshot-*' |
    Sort-Object CreationTimeUtc -Descending | Select-Object -First 1
if ($null -eq $snapshot) { throw 'The Docker snapshot was not created.' }
& (Join-Path $workspace 'ops/restore.ps1') -BackupPath $snapshot.FullName -ConfirmRestore
& (Join-Path $workspace 'ops/start-local.ps1') -TimeoutSeconds 300
$restoredLogin = Invoke-Api 'POST' '/auth/admin/login' @{ username = [string]$state.Values['PLATFORM_ADMIN_USERNAME']; password = $changedPassword }
$restoredSession = Invoke-Api 'GET' '/session' $null $restoredLogin.accessToken
$restoredOperators = @(Invoke-Api 'GET' '/platform/users' $null $restoredLogin.accessToken)
if (@($restoredSession.tenants).Count -ne 1 -or $restoredOperators.Count -ne 1 -or $restoredOperators[0].username -ne 'ci-operator') {
    throw 'The coordinated Docker restore did not preserve the real account and tenant records.'
}
Write-Host 'PASS: built images, empty startup, superadmin and operator allocation, role restriction, runtime RLS boundary, NATS authentication and a coordinated backup/restore round trip.'

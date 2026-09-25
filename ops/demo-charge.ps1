param(
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 60,
    [string]$EnvironmentFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env.docker')
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw '.env.docker was not found. Run docker-start.cmd first.'
}

$adminPort = ((Get-Content -LiteralPath $environmentFile | Where-Object { $_ -match '^ADMIN_WEB_PORT=' }) -split '=', 2)[1]
$api = "http://127.0.0.1:$adminPort/api/v1"
$tenantId = '11111111-1111-1111-1111-111111111111'
$headers = @{ 'X-Tenant-Id' = $tenantId }
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)

Write-Host 'Waiting for the 12-connector simulator...'
do {
    try {
        $devices = Invoke-RestMethod -Uri "$api/admin/assets/devices" -Headers $headers -TimeoutSec 5
        $device = $devices | Where-Object { $_.deviceCode -eq 'PILE001' } | Select-Object -First 1
    }
    catch { $device = $null }
    if ($device.status -ne 'ONLINE') { Start-Sleep -Seconds 1 }
} while ($device.status -ne 'ONLINE' -and [DateTime]::UtcNow -lt $deadline)
if ($device.status -ne 'ONLINE') { throw 'The simulator is offline. Check the simulator and device-gateway container logs.' }
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)

$orderHeaders = @{
    'X-Tenant-Id' = $tenantId
    'Idempotency-Key' = "docker-demo-order-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
}
$orderBody = @{ connectorId = '44444444-4444-4444-4444-000000000001' } | ConvertTo-Json
$orderRequest = @{
    Method = 'Post'; Uri = "$api/charging/orders"; Headers = $orderHeaders
    ContentType = 'application/json'; Body = $orderBody; TimeoutSec = 10
}
$order = Invoke-RestMethod @orderRequest
if (-not $order.orderId) { throw 'Order creation response is missing orderId.' }
Write-Host "Order created: $($order.orderNo). The simulator is charging..." -ForegroundColor Cyan

do {
    Start-Sleep -Seconds 2
    $orders = Invoke-RestMethod -Uri "$api/charging/orders/mine" -Headers $headers -TimeoutSec 5
    $current = $orders | Where-Object { $_.orderId -eq $order.orderId } | Select-Object -First 1
    if ($current.status -in @('FAILED', 'CANCELLED')) {
        throw "The order failed: $($current.status)"
    }
} while ($current.status -ne 'COMPLETED' -and [DateTime]::UtcNow -lt $deadline)
if ($current.status -ne 'COMPLETED') { throw "The order did not complete in time. Current status: $($current.status)" }

$paymentHeaders = @{
    'X-Tenant-Id' = $tenantId
    'Idempotency-Key' = "docker-demo-payment-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
}
$paymentBody = @{ orderId = $order.orderId; channel = 'WECHAT' } | ConvertTo-Json
$paymentRequest = @{
    Method = 'Post'; Uri = "$api/payments"; Headers = $paymentHeaders
    ContentType = 'application/json'; Body = $paymentBody; TimeoutSec = 10
}
$payment = Invoke-RestMethod @paymentRequest
if (-not $payment.paymentId -or $payment.clientParameters.mode -ne 'LOCAL_SIMULATION') {
    throw 'Expected a local simulated payment intent.'
}
$completePaymentRequest = @{
    Method = 'Post'; Uri = "$api/payments/$($payment.paymentId)/simulate-success"
    Headers = $headers; TimeoutSec = 10
}
$completedPayment = Invoke-RestMethod @completePaymentRequest
if ($completedPayment.status -ne 'SUCCEEDED') { throw 'Simulated payment did not succeed.' }
$orders = Invoke-RestMethod -Uri "$api/charging/orders/mine" -Headers $headers -TimeoutSec 5
$current = $orders | Where-Object { $_.orderId -eq $order.orderId } | Select-Object -First 1
if (-not $current -or $current.payableAmountMinor -le 0 -or
    $current.paidAmountMinor -ne $current.payableAmountMinor) {
    throw 'Payment was not reflected in the order balance.'
}

Write-Host ''
Write-Host 'Flow passed: order, device start, metering, settlement, and simulated WeChat payment.' -ForegroundColor Green
Write-Host "Order number: $($order.orderNo)"
Write-Host "Energy: $($current.energyWh) Wh"
Write-Host "Paid: $($current.payableAmountMinor) fen"

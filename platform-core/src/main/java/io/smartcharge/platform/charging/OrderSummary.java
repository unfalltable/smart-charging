package io.smartcharge.platform.charging;

import java.util.UUID;

record OrderSummary(UUID orderId, String orderNo, String stationName, int connectorNo,
                    ChargeOrderStatus status, long energyWh, long payableAmountMinor, long paidAmountMinor) { }

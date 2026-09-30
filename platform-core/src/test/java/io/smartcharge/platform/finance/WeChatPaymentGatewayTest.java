package io.smartcharge.platform.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wechat.pay.java.service.refund.model.Status;
import com.wechat.pay.java.core.exception.ServiceException;
import io.smartcharge.platform.shared.domain.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WeChatPaymentGatewayTest {
    @Test
    void onlyWhitelistedExplicitRejectionsAreClassifiedAndAmbiguousErrorsStayPending() {
        assertThat(WeChatPaymentGateway.classifyCreateFailure(providerError(400, "PARAM_ERROR")))
                .isInstanceOf(ProviderRequestRejectedException.class);
        assertThat(WeChatPaymentGateway.classifyCreateFailure(providerError(403, "NOT_ENOUGH")))
                .isInstanceOf(ProviderRequestRejectedException.class);
        assertThat(WeChatPaymentGateway.classifyCreateFailure(providerError(401, "SIGN_ERROR")))
                .isInstanceOf(ProviderRequestRejectedException.class);
        for (ServiceException ambiguous : new ServiceException[] {
                providerError(500, "SYSTEM_ERROR"), providerError(429, "FREQUENCY_LIMITED"),
                providerError(400, "UNKNOWN_BUSINESS_STATE"), providerError(403, "OUT_TRADE_NO_USED"),
                providerError(403, "ORDERPAID"), new ServiceException(null, 404, "{}") }) {
            assertThat(WeChatPaymentGateway.classifyCreateFailure(ambiguous)).isSameAs(ambiguous);
        }
    }

    @Test
    void onlyOfficialNotFoundResponsesPermitResubmittingTheOriginalMerchantNumber() {
        assertThat(WeChatPaymentGateway.resourceMissing(providerError(404, "RESOURCE_NOT_EXISTS"))).isTrue();
        assertThat(WeChatPaymentGateway.resourceMissing(providerError(404, "ORDER_NOT_EXIST"))).isTrue();
        assertThat(WeChatPaymentGateway.resourceMissing(providerError(500, "RESOURCE_NOT_EXISTS"))).isFalse();
        assertThat(WeChatPaymentGateway.resourceMissing(providerError(404, "MCH_NOT_EXISTS"))).isFalse();
        assertThat(WeChatPaymentGateway.resourceMissing(new ServiceException(null, 404, "{}"))).isFalse();
    }

    private ServiceException providerError(int status, String code) {
        return new ServiceException(null, status, "{\"code\":\"" + code + "\",\"message\":\"provider detail\"}");
    }

    @Test
    void abnormalRefundKeepsTheRefundReservationUntilItReallyClosesOrSucceeds() {
        assertThat(WeChatPaymentGateway.refundState(Status.ABNORMAL)).isEqualTo(PaymentGateway.ProviderState.PENDING);
        assertThat(WeChatPaymentGateway.refundState(Status.PROCESSING)).isEqualTo(PaymentGateway.ProviderState.PENDING);
        assertThat(WeChatPaymentGateway.refundState(Status.CLOSED)).isEqualTo(PaymentGateway.ProviderState.FAILED);
        assertThat(WeChatPaymentGateway.refundState(Status.SUCCESS)).isEqualTo(PaymentGateway.ProviderState.SUCCEEDED);
    }

    @Test
    void financialDateComesFromProviderEvidenceInsteadOfDelayedCallbackArrival() {
        assertThat(WeChatPaymentGateway.successTime("2026-09-29T23:59:59+08:00", true))
                .isEqualTo(Instant.parse("2026-09-29T15:59:59Z"));
        assertThat(WeChatPaymentGateway.successTime(null, false)).isNull();
        assertThatThrownBy(() -> WeChatPaymentGateway.successTime(null, true)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> WeChatPaymentGateway.successTime("invalid", true)).isInstanceOf(DomainException.class);
    }
}

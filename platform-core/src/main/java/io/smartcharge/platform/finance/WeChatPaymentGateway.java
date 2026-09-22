package io.smartcharge.platform.finance;

import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Status;
import io.smartcharge.platform.shared.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
final class WeChatPaymentGateway implements PaymentGateway {
    private final MerchantChannelRepository channels;
    private final EnvironmentSecretMaterialProvider secrets;
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();

    WeChatPaymentGateway(MerchantChannelRepository channels, EnvironmentSecretMaterialProvider secrets) {
        this.channels = channels;
        this.secrets = secrets;
    }

    @Override
    public boolean supports(String channel) {
        return "WECHAT".equals(channel);
    }

    @Override
    public GatewayIntent createPayment(GatewayPayment payment) {
        if (payment.payerSubject() == null || payment.payerSubject().isBlank()) {
            throw new DomainException("Customer has no WeChat payment identity");
        }
        if (payment.amountMinor() > Integer.MAX_VALUE) throw new DomainException("Payment amount exceeds channel limit");
        Client client = client(payment.tenantId());
        PrepayRequest request = new PrepayRequest();
        request.setAppid(client.channel().applicationId());
        request.setMchid(client.channel().merchantId());
        request.setDescription(payment.description());
        request.setOutTradeNo(payment.merchantOrderNo());
        request.setNotifyUrl(requireHttps(client.channel().notifyUrl(), "payment notify URL"));
        Amount amount = new Amount();
        amount.setTotal(Math.toIntExact(payment.amountMinor()));
        amount.setCurrency(payment.currency());
        request.setAmount(amount);
        Payer payer = new Payer();
        payer.setOpenid(payment.payerSubject());
        request.setPayer(payer);
        PrepayWithRequestPaymentResponse response = client.jsapi().prepayWithRequestPayment(request);
        Map<String, String> parameters = Map.of(
                "appId", response.getAppId(), "timeStamp", response.getTimeStamp(),
                "nonceStr", response.getNonceStr(), "package", response.getPackageVal(),
                "signType", response.getSignType(), "paySign", response.getPaySign());
        return new GatewayIntent(response.getPackageVal(), parameters);
    }

    @Override
    public GatewayRefund createRefund(GatewayRefundRequest refund) {
        Client client = client(refund.tenantId());
        CreateRequest request = new CreateRequest();
        request.setTransactionId(refund.providerTransactionNo());
        request.setOutRefundNo(refund.merchantRefundNo());
        request.setReason(refund.reason());
        request.setNotifyUrl(requireHttps(client.channel().refundNotifyUrl(), "refund notify URL"));
        AmountReq amount = new AmountReq();
        amount.setRefund(refund.amountMinor());
        amount.setTotal(refund.originalPaymentAmountMinor());
        amount.setCurrency(refund.currency());
        request.setAmount(amount);
        Refund response = client.refunds().create(request);
        return new GatewayRefund(response.getRefundId(), response.getStatus() == Status.SUCCESS);
    }

    @Override
    public GatewayPaymentStatus queryPayment(UUID tenantId, String merchantOrderNo) {
        Client client = client(tenantId);
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(client.channel().merchantId());
        request.setOutTradeNo(merchantOrderNo);
        Transaction transaction = client.jsapi().queryOrderByOutTradeNo(request);
        ProviderState state = switch (transaction.getTradeState()) {
            case SUCCESS -> ProviderState.SUCCEEDED;
            case CLOSED, REVOKED, PAYERROR -> ProviderState.FAILED;
            default -> ProviderState.PENDING;
        };
        long amount = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                ? 0 : transaction.getAmount().getTotal();
        return new GatewayPaymentStatus(transaction.getTransactionId(), amount, state);
    }

    @Override
    public GatewayRefundStatus queryRefund(UUID tenantId, String merchantRefundNo) {
        Client client = client(tenantId);
        QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
        request.setOutRefundNo(merchantRefundNo);
        Refund refund = client.refunds().queryByOutRefundNo(request);
        ProviderState state = switch (refund.getStatus()) {
            case SUCCESS -> ProviderState.SUCCEEDED;
            case CLOSED, ABNORMAL -> ProviderState.FAILED;
            default -> ProviderState.PENDING;
        };
        long amount = refund.getAmount() == null || refund.getAmount().getRefund() == null
                ? 0 : refund.getAmount().getRefund();
        return new GatewayRefundStatus(refund.getRefundId(), amount, state);
    }

    @Override
    public VerifiedCallback verifyCallback(UUID tenantId, Map<String, String> headers, String body) {
        Client client = client(tenantId);
        Transaction transaction = client.parser().parse(request(headers, body), Transaction.class);
        if (!client.channel().merchantId().equals(transaction.getMchid())
                || !client.channel().applicationId().equals(transaction.getAppid())) {
            throw new DomainException("WeChat callback merchant identity mismatch");
        }
        return new VerifiedCallback(eventId(headers, body), transaction.getOutTradeNo(),
                transaction.getTransactionId(), transaction.getAmount().getTotal(),
                transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS, body);
    }

    @Override
    public VerifiedRefundCallback verifyRefundCallback(UUID tenantId, Map<String, String> headers, String body) {
        Client client = client(tenantId);
        RefundNotification refund = client.parser().parse(request(headers, body), RefundNotification.class);
        return new VerifiedRefundCallback(eventId(headers, body), refund.getOutRefundNo(), refund.getRefundId(),
                refund.getAmount().getRefund(), refund.getRefundStatus() == Status.SUCCESS, body);
    }

    private Client client(UUID tenantId) {
        MerchantChannelRepository.Configuration channel = channels.requireActive(tenantId, "WECHAT");
        Client existing = clients.get(channel.id());
        if (existing != null && existing.channel().version() == channel.version()) return existing;
        EnvironmentSecretMaterialProvider.WeChatMaterial material = secrets.weChat(channel.secretReference());
        RSAPublicKeyConfig config = new RSAPublicKeyConfig.Builder()
                .merchantId(channel.merchantId())
                .privateKeyFromPath(material.privateKeyPath())
                .merchantSerialNumber(material.merchantSerial())
                .apiV3Key(material.apiV3Key())
                .publicKeyFromPath(material.publicKeyPath())
                .publicKeyId(material.publicKeyId())
                .build();
        Client created = new Client(channel,
                new JsapiServiceExtension.Builder().config(config).signType("RSA").build(),
                new RefundService.Builder().config(config).build(), new NotificationParser(config));
        clients.put(channel.id(), created);
        return created;
    }

    private static RequestParam request(Map<String, String> headers, String body) {
        return new RequestParam.Builder()
                .serialNumber(header(headers, "Wechatpay-Serial"))
                .nonce(header(headers, "Wechatpay-Nonce"))
                .signature(header(headers, "Wechatpay-Signature"))
                .timestamp(header(headers, "Wechatpay-Timestamp"))
                .signType(optionalHeader(headers, "Wechatpay-Signature-Type"))
                .body(body).build();
    }

    private static String header(Map<String, String> headers, String name) {
        String value = optionalHeader(headers, name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing WeChat callback header: " + name);
        return value;
    }

    private static String optionalHeader(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String requireHttps(String value, String label) {
        if (value == null || !value.startsWith("https://")) throw new DomainException("WeChat " + label + " must use HTTPS");
        return value;
    }

    private static String eventId(Map<String, String> headers, String body) {
        return sha256(optionalHeader(headers, "Wechatpay-Timestamp") + ":"
                + optionalHeader(headers, "Wechatpay-Nonce") + ":" + body);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Client(MerchantChannelRepository.Configuration channel,
                          JsapiServiceExtension jsapi, RefundService refunds,
                          NotificationParser parser) { }
}

package com.zrl.rlpay.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.zrl.rlpay.payment.channel.ChannelResultProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.zrl.rlpay.payment.stripe.StripeCheckoutService.CHANNEL_CODE;
import static com.zrl.rlpay.payment.stripe.StripeSandboxProtocol.*;

/** 负责 Stripe 测试 Checkout 的发起与结果查询。 */
@Component
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeCheckoutClient implements ChannelResultProvider {
    private final RestClient api;
    private final URI successUrl;
    private final URI cancelUrl;

    /** 绑定受控 Stripe 客户端，并校验 Checkout 返回地址配置。 */
    public StripeCheckoutClient(@Qualifier("stripeSandboxRestClient") RestClient api,
            @Value("${rlpay.stripe-sandbox.success-url:}") String successUrl,
            @Value("${rlpay.stripe-sandbox.cancel-url:}") String cancelUrl) {
        this.api = api;
        this.successUrl = https(URI.create(successUrl));
        this.cancelUrl = https(URI.create(cancelUrl));
    }

    /** 创建仅支持 USD 卡支付的测试 Checkout Session。 */
    public Checkout create(String paymentNo, long amountCent) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "payment");
        form.add("success_url", successUrl.toString());
        form.add("cancel_url", cancelUrl.toString());
        form.add("payment_method_types[0]", "card");
        form.add("client_reference_id", paymentNo);
        form.add("metadata[rlpay_order]", paymentNo);
        form.add("payment_intent_data[metadata][rlpay_order]", paymentNo);
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", CURRENCY);
        form.add("line_items[0][price_data][unit_amount]", Long.toString(amountCent));
        form.add("line_items[0][price_data][product_data][name]", "RLpay sandbox order");
        JsonNode response = api.post().uri("/v1/checkout/sessions")
                .header("Idempotency-Key", "rlpay-checkout-" + paymentNo)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        String tradeNo = validateSession(response);
        if (!paymentNo.equals(response.path("metadata").path("rlpay_order").asText())
                || response.path("amount_total").longValue() != amountCent) {
            throw new IllegalStateException("Stripe 测试会话与平台订单不符");
        }
        URI url = https(URI.create(response.path("url").asText()));
        if (!"checkout.stripe.com".equalsIgnoreCase(url.getHost()) || url.toString().length() > 2048) {
            throw new IllegalStateException("Stripe 返回了未知付款地址");
        }
        return new Checkout(tradeNo, url.toString());
    }

    /** 返回用于路由的 Stripe 测试渠道编码。 */
    @Override
    public String channelCode() {
        return CHANNEL_CODE;
    }

    /** 查询测试会话；成功时间取已支付卡交易的渠道时间。 */
    @Override
    public Optional<Result> queryResult(String tradeNo) {
        if (!isSession(tradeNo)) {
            return Optional.empty();
        }
        JsonNode session = api.get().uri(builder -> builder.path("/v1/checkout/sessions/{id}")
                        .queryParam("expand[]", "payment_intent.latest_charge").build(tradeNo))
                .retrieve().body(JsonNode.class);
        if (!tradeNo.equals(validateSession(session))) {
            throw new IllegalStateException("Stripe 查单返回了其他会话");
        }
        long amount = session.path("amount_total").longValue();
        if (!"paid".equals(session.path("payment_status").asText())) {
            return Optional.of(new Result(CHANNEL_CODE, tradeNo, amount, "PENDING", null));
        }
        JsonNode intent = session.path("payment_intent");
        JsonNode charge = intent.path("latest_charge");
        if (!"succeeded".equals(intent.path("status").asText()) || !charge.path("paid").asBoolean(false)
                || charge.path("created").longValue() <= 0 || charge.path("amount").longValue() != amount) {
            throw new IllegalStateException("Stripe 尚未返回可核实的支付成功记录");
        }
        return Optional.of(new Result(CHANNEL_CODE, tradeNo, amount, "SUCCESS",
                Instant.ofEpochSecond(charge.path("created").longValue())));
    }

    /** 仅允许不含凭据的 HTTPS URL。 */
    private static URI https(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Stripe 地址必须使用 HTTPS");
        }
        return url;
    }

    /** 创建会话的渠道标识和付款链接。 */
    public record Checkout(String tradeNo, String url) {
    }
}

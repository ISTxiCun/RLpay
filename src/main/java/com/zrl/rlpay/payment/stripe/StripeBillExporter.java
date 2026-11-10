package com.zrl.rlpay.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashSet;

import static com.zrl.rlpay.payment.stripe.StripeCheckoutService.CHANNEL_CODE;
import static com.zrl.rlpay.payment.stripe.StripeSandboxProtocol.CURRENCY;
import static com.zrl.rlpay.payment.stripe.StripeSandboxProtocol.validateSession;

/** 负责 Stripe 成功卡交易分页和支付账单导出。 */
@Component
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeBillExporter {
    private final RestClient api;

    /** 复用受控 Stripe HTTP 客户端。 */
    public StripeBillExporter(@Qualifier("stripeSandboxRestClient") RestClient api) {
        this.api = api;
    }

    /** 从 Stripe 测试卡交易分页生成支付账单，使用 Checkout 会话号对账。 */
    public String downloadBill(Instant from, Instant to) {
        StringBuilder csv = new StringBuilder("channel_code,channel_trade_no,amount_cent,status,paid_at\n");
        String after = null;
        var trades = new HashSet<String>();
        for (int page = 0; page < 100; page++) {
            String cursor = after;
            JsonNode response = api.get().uri(builder -> {
                var uri = builder.path("/v1/charges").queryParam("limit", 100)
                        .queryParam("created[gte]", from.getEpochSecond())
                        .queryParam("created[lt]", to.getEpochSecond());
                if (cursor != null) {
                    uri.queryParam("starting_after", cursor);
                }
                return uri.build();
            }).retrieve().body(JsonNode.class);
            if (response == null || !response.path("data").isArray() || !response.path("has_more").isBoolean()) {
                throw new IllegalStateException("Stripe 交易列表格式错误");
            }
            for (JsonNode charge : response.path("data")) {
                after = charge.path("id").asText();
                String paymentNo = charge.path("metadata").path("rlpay_order").asText();
                if (paymentNo.isEmpty() || !"succeeded".equals(charge.path("status").asText())
                        || !charge.path("paid").asBoolean() || !charge.path("captured").asBoolean()) {
                    continue;
                }
                String intent = charge.path("payment_intent").asText();
                long amount = charge.path("amount").longValue();
                Instant created = Instant.ofEpochSecond(charge.path("created").longValue());
                if (!charge.path("livemode").isBoolean() || charge.path("livemode").asBoolean()
                        || !CURRENCY.equals(charge.path("currency").asText()) || !intent.matches("pi_[A-Za-z0-9]+")
                        || !charge.path("amount").isIntegralNumber() || !charge.path("amount").canConvertToLong()
                        || amount <= 0 || created.isBefore(from) || !created.isBefore(to)) {
                    throw new IllegalStateException("Stripe 账单包含无法核实的交易");
                }
                String trade = findSession(intent, paymentNo, amount);
                if (!trades.add(trade)) {
                    throw new IllegalStateException("Stripe 账单存在重复会话");
                }
                csv.append(CHANNEL_CODE).append(',').append(trade).append(',').append(amount)
                        .append(",SUCCESS,").append(created).append('\n');
                if (csv.length() > 1024 * 1024) {
                    throw new IllegalStateException("Stripe 支付账单超过导入大小限制");
                }
            }
            if (!response.path("has_more").asBoolean()) {
                return csv.toString();
            }
            if (after == null || !after.matches("ch_[A-Za-z0-9]+") || after.equals(cursor)) {
                throw new IllegalStateException("Stripe 交易分页游标无效");
            }
        }
        throw new IllegalStateException("Stripe 交易账单超过测试分页上限");
    }

    /** 通过 PaymentIntent 查找并验证属于本项目的 Checkout 会话。 */
    private String findSession(String intent, String paymentNo, long amount) {
        JsonNode page = api.get().uri(builder -> builder.path("/v1/checkout/sessions")
                        .queryParam("payment_intent", intent).queryParam("limit", 2).build())
                .retrieve().body(JsonNode.class);
        if (page == null || !page.path("data").isArray() || page.path("data").size() != 1
                || page.path("has_more").asBoolean()) {
            throw new IllegalStateException("Stripe 卡交易没有唯一的 Checkout 会话");
        }
        JsonNode session = page.path("data").get(0);
        String trade = validateSession(session);
        if (!paymentNo.equals(session.path("metadata").path("rlpay_order").asText())
                || !intent.equals(session.path("payment_intent").asText())
                || !"paid".equals(session.path("payment_status").asText())
                || session.path("amount_total").longValue() != amount) {
            throw new IllegalStateException("Stripe 卡交易与 Checkout 会话不一致");
        }
        return trade;
    }
}

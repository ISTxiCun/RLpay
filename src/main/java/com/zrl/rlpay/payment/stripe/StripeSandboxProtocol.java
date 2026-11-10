package com.zrl.rlpay.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/** Stripe 测试会话的共享字段约定。 */
final class StripeSandboxProtocol {
    static final String CURRENCY = "usd";
    private static final Pattern SESSION = Pattern.compile("cs_test_[A-Za-z0-9]{1,247}");

    /** 只提供协议常量与校验。 */
    private StripeSandboxProtocol() {
    }

    /** 确认响应属于测试 Checkout Session 且币种和金额格式符合约定。 */
    static String validateSession(JsonNode session) {
        if (session == null || !session.path("livemode").isBoolean() || session.path("livemode").asBoolean()
                || !CURRENCY.equals(session.path("currency").asText()) || !session.path("amount_total").isIntegralNumber()
                || !session.path("amount_total").canConvertToLong() || session.path("amount_total").longValue() <= 0
                || !isSession(session.path("id").asText())) {
            throw new IllegalStateException("Stripe 测试会话响应不符合约定");
        }
        return session.path("id").asText();
    }

    /** 检查渠道交易号可安全放入 API 路径。 */
    static boolean isSession(String tradeNo) {
        return tradeNo != null && SESSION.matcher(tradeNo).matches();
    }
}

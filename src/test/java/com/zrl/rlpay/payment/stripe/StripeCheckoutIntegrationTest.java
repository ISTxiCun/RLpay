package com.zrl.rlpay.payment.stripe;

import com.zrl.rlpay.payment.channel.ChannelResultProvider;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用 MySQL 和 Stripe 替身验证超时后的关联恢复及平台成功迁移。 */
@SpringBootTest(properties = {
        "rlpay.payment.jobs-enabled=false", "rlpay.stripe-sandbox.enabled=true",
        "RLPAY_STRIPE_TEST_SECRET_KEY=sk_test_fixture_not_a_real_key",
        "RLPAY_STRIPE_WEBHOOK_SECRET=whsec_fixture_not_a_real_key",
        "rlpay.stripe-sandbox.success-url=https://example.invalid/success",
        "rlpay.stripe-sandbox.cancel-url=https://example.invalid/cancel",
        "rlpay.merchant-api.enabled=true",
        "rlpay.merchant-api.credentials[0].id=stripe-smoke",
        "rlpay.merchant-api.credentials[0].merchant-code=IT-STRIPE",
        "rlpay.merchant-api.credentials[0].secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "rlpay.merchant-api.credentials[0].enabled=true"
})
@AutoConfigureMockMvc
class StripeCheckoutIntegrationTest {
    @Autowired private PaymentOrderService orders;
    @Autowired private StripeCheckoutService checkout;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private StripeCheckoutClient stripe;

    /** 首次超时保留原平台单，回调提前到达时重试，绑定后两次确认保持幂等。 */
    @Test
    void timeoutPreservesOrderAndEarlyCallbackCanRetry() {
        String merchant = "IT-STRIPE";
        var order = orders.createOrGet(merchant, UUID.randomUUID().toString(), 1290);
        String paymentNo = order.paymentOrderNo();
        String tradeNo = "cs_test_" + "b".repeat(58);
        try {
            doThrow(new ResourceAccessException("fixture timeout"))
                    .doReturn(new StripeCheckoutClient.Checkout(tradeNo, "https://checkout.stripe.com/c/pay/demo"))
                    .when(stripe).create(paymentNo, 1290);
            assertThatThrownBy(() -> checkout.start(merchant, paymentNo))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
            assertThat(checkout.confirm(tradeNo)).isFalse();
            assertThat(checkout.start(merchant, paymentNo).status()).isEqualTo("PENDING");
            assertThat(checkout.start(merchant, paymentNo).paymentUrl()).endsWith("/demo");
            verify(stripe, times(2)).create(paymentNo, 1290);
            doReturn(Optional.of(new ChannelResultProvider.Result(StripeCheckoutService.CHANNEL_CODE, tradeNo,
                    1290, "SUCCESS", Instant.parse("2026-11-11T00:30:00Z")))).when(stripe).queryResult(tradeNo);
            assertThat(checkout.confirm(tradeNo)).isTrue();
            assertThat(checkout.confirm(tradeNo)).isTrue();
            assertThat(checkout.start(merchant, paymentNo).paymentUrl()).isNull();
            assertThat(orders.findByPaymentOrderNo(merchant, paymentNo).orElseThrow().status()).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification WHERE payment_order_no = ?",
                    Integer.class, paymentNo)).isZero();
        } finally {
            jdbc.update("DELETE FROM stripe_checkout_attempt WHERE payment_order_no = ?", paymentNo);
            jdbc.update("DELETE FROM payment_order WHERE payment_order_no = ?", paymentNo);
        }
    }

    /** 验证真实 HTTP 路由、两套验签和 MySQL 状态写入，只有 Stripe 网络被替换。 */
    @Test
    void signedHttpCheckoutAndWebhookReachSuccess() throws Exception {
        var order = orders.createOrGet("IT-STRIPE", UUID.randomUUID().toString(), 1290);
        String paymentNo = order.paymentOrderNo();
        String tradeNo = "cs_test_" + "c".repeat(58);
        String orderPath = "/api/merchant/payments/" + paymentNo;
        String webhook = "/api/payment/webhooks/stripe-test";
        try {
            doReturn(new StripeCheckoutClient.Checkout(tradeNo, "https://checkout.stripe.com/c/pay/demo"))
                    .when(stripe).create(paymentNo, 1290);
            doReturn(Optional.of(new ChannelResultProvider.Result(StripeCheckoutService.CHANNEL_CODE, tradeNo,
                    1290, "SUCCESS", Instant.now()))).when(stripe).queryResult(tradeNo);
            mvc.perform(merchantRequest(post(orderPath + "/stripe-checkout"), "POST", orderPath + "/stripe-checkout"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
            String body = """
                    {"livemode":false,"type":"checkout.session.completed","data":{"object":{"id":"%s"}}}
                    """.formatted(tradeNo);
            String time = Long.toString(Instant.now().getEpochSecond());
            mvc.perform(post(webhook).content(body).header("Stripe-Signature", "t=" + time + ",v1=" + "0".repeat(64)))
                    .andExpect(status().isBadRequest());
            mvc.perform(merchantRequest(get(orderPath), "GET", orderPath))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
            String signature = sign("whsec_fixture_not_a_real_key".getBytes(StandardCharsets.UTF_8), time + "." + body);
            mvc.perform(post(webhook).content(body).header("Stripe-Signature", "t=" + time + ",v1=" + signature))
                    .andExpect(status().isNoContent());
            mvc.perform(merchantRequest(get(orderPath), "GET", orderPath))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        } finally {
            jdbc.update("DELETE FROM stripe_checkout_attempt WHERE payment_order_no = ?", paymentNo);
            jdbc.update("DELETE FROM payment_order WHERE payment_order_no = ?", paymentNo);
            jdbc.update("DELETE FROM merchant_request_nonce WHERE credential_id = ?", "stripe-smoke");
        }
    }

    /** 新工作进程从数据库重领发起记录；过期记录不再次调用 Stripe。 */
    @Test
    void recoveryReplaysOnlyWithinPersistedWindow() {
        String merchant = "IT-STRIPE";
        String payment = orders.createOrGet(merchant, UUID.randomUUID().toString(), 1290).paymentOrderNo();
        String expired = orders.createOrGet(merchant, UUID.randomUUID().toString(), 1290).paymentOrderNo();
        String trade = "cs_test_" + "d".repeat(58);
        try {
            doThrow(new ResourceAccessException("accepted before response was lost"))
                    .doReturn(new StripeCheckoutClient.Checkout(trade, "https://checkout.stripe.com/c/pay/demo"))
                    .when(stripe).create(payment, 1290);
            assertThatThrownBy(() -> checkout.start(merchant, payment)).isInstanceOf(ResponseStatusException.class);
            assertThat(checkout.confirm(trade)).isFalse();
            jdbc.update("UPDATE stripe_checkout_attempt SET next_recovery_at_utc = UTC_TIMESTAMP(3) WHERE payment_order_no = ?", payment);
            jdbc.update("""
                    INSERT INTO stripe_checkout_attempt (payment_order_no, requested_at_utc)
                    VALUES (?, UTC_TIMESTAMP(3) - INTERVAL 24 HOUR)
                    """, expired);

            new StripeCheckoutRecovery(jdbc, checkout).recoverDue();
            assertThat(orders.findByPaymentOrderNo(merchant, payment).orElseThrow().status()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT channel_trade_no FROM payment_order WHERE payment_order_no = ?",
                    String.class, payment)).isEqualTo(trade);
            doReturn(Optional.of(new ChannelResultProvider.Result(StripeCheckoutService.CHANNEL_CODE, trade,
                    1290, "SUCCESS", Instant.now()))).when(stripe).queryResult(trade);
            assertThat(checkout.confirm(trade)).isTrue();

            new StripeCheckoutRecovery(jdbc, checkout).recoverDue();
            assertThatThrownBy(() -> checkout.start(merchant, expired)).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("幂等窗口已过");
            verify(stripe, times(2)).create(payment, 1290);
            verify(stripe, never()).create(expired, 1290);
        } finally {
            jdbc.update("DELETE FROM stripe_checkout_attempt WHERE payment_order_no IN (?, ?)", payment, expired);
            jdbc.update("DELETE FROM payment_order WHERE payment_order_no IN (?, ?)", payment, expired);
        }
    }

    /** 生成签名后的空请求体商户请求。 */
    private static MockHttpServletRequestBuilder merchantRequest(MockHttpServletRequestBuilder request,
            String method, String path) throws Exception {
        String time = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String canonical = String.join("\n", method, path, "stripe-smoke", time, nonce, hash);
        return request.header("X-Key-Id", "stripe-smoke").header("X-Timestamp", time)
                .header("X-Nonce", nonce).header("X-Signature", sign(new byte[32], canonical));
    }

    /** 对规范串或 Webhook 原始内容签名。 */
    private static String sign(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}

package com.zrl.rlpay.payment.stripe;

import com.zrl.rlpay.payment.channel.ChannelResultProvider;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.mockito.Mockito.verify;

/** 用 MySQL 和 Stripe 替身验证超时后的关联恢复及平台成功迁移。 */
@SpringBootTest(properties = {
        "rlpay.payment.jobs-enabled=false", "rlpay.stripe-sandbox.enabled=true",
        "RLPAY_STRIPE_TEST_SECRET_KEY=sk_test_fixture_not_a_real_key",
        "RLPAY_STRIPE_WEBHOOK_SECRET=whsec_fixture_not_a_real_key",
        "rlpay.stripe-sandbox.success-url=https://example.invalid/success",
        "rlpay.stripe-sandbox.cancel-url=https://example.invalid/cancel"
})
class StripeCheckoutIntegrationTest {
    @Autowired private PaymentOrderService orders;
    @Autowired private StripeCheckoutService checkout;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StripeSandboxClient stripe;

    /** 首次超时保留原平台单，回调提前到达时重试，绑定后两次确认保持幂等。 */
    @Test
    void timeoutPreservesOrderAndEarlyCallbackCanRetry() {
        String merchant = "IT-STRIPE";
        var order = orders.createOrGet(merchant, UUID.randomUUID().toString(), 1290);
        String paymentNo = order.paymentOrderNo();
        String tradeNo = "cs_test_" + "b".repeat(58);
        try {
            doThrow(new ResourceAccessException("fixture timeout"))
                    .doReturn(new StripeSandboxClient.Checkout(tradeNo, "https://checkout.stripe.com/c/pay/demo"))
                    .when(stripe).create(paymentNo, 1290);
            assertThatThrownBy(() -> checkout.start(merchant, paymentNo))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
            assertThat(checkout.confirm(tradeNo)).isFalse();
            assertThat(checkout.start(merchant, paymentNo).status()).isEqualTo("PENDING");
            assertThat(checkout.start(merchant, paymentNo).paymentUrl()).endsWith("/demo");
            verify(stripe, times(2)).create(paymentNo, 1290);
            doReturn(Optional.of(new ChannelResultProvider.Result(StripeSandboxClient.CODE, tradeNo,
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
}

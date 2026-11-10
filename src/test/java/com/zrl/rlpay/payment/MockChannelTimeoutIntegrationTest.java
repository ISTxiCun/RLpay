package com.zrl.rlpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.channel.MockPaymentChannel;
import com.zrl.rlpay.payment.job.PaymentResultJobs;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实数据库验证渠道已受理但商城收到 503 时的重试。 */
@SpringBootTest(properties = "rlpay.payment.jobs-enabled=false")
@AutoConfigureMockMvc
class MockChannelTimeoutIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentOrderService orders;
    @Autowired private MockPaymentChannel channel;
    @Autowired private PaymentResultJobs jobs;

    /** 正常测试时提供开关；单独验证可直接从进程环境传入。 */
    @DynamicPropertySource
    static void enableTimeout(DynamicPropertyRegistry registry) {
        if (System.getenv("RLPAY_MOCK_CHANNEL_TIMEOUT_ON_FIRST_ACCEPT") == null) {
            registry.add("RLPAY_MOCK_CHANNEL_TIMEOUT_ON_FIRST_ACCEPT", () -> "true");
        }
    }

    /** 首次超时保留 CREATED，并发重试都绑定同一笔已提交的渠道交易。 */
    @Test
    void retryReusesAcceptedTrade() throws Exception {
        String body = mvc.perform(post("/api/mall/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productCode":"MALL-NOTEBOOK","quantity":1}]}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(body).path("merchantOrderNo").asText();
        String paymentNo = null;
        try {
            mvc.perform(post("/api/mall/orders/{orderNo}/payment", orderNo))
                    .andExpect(status().isServiceUnavailable());
            var local = orders.findByMerchantOrderNo(MallPaymentGateway.MERCHANT_CODE, orderNo).orElseThrow();
            paymentNo = local.paymentOrderNo();
            assertThat(local.status()).isEqualTo("CREATED");
            String tradeNo = jdbc.queryForObject("""
                    SELECT channel_trade_no FROM mock_channel_trade WHERE payment_order_no = ?
                    """, String.class, paymentNo);
            assertThat(tradeNo).startsWith("MOCK-");

            String stablePaymentNo = paymentNo;
            var pool = Executors.newFixedThreadPool(2);
            var ready = new CountDownLatch(2);
            try {
                var results = pool.invokeAll(java.util.List.of(
                        () -> retry(orderNo, stablePaymentNo, tradeNo, ready),
                        () -> retry(orderNo, stablePaymentNo, tradeNo, ready)), 15, TimeUnit.SECONDS);
                for (var result : results) {
                    result.get();
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_channel_trade WHERE payment_order_no = ?",
                    Integer.class, paymentNo)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?",
                    Integer.class, MallPaymentGateway.MERCHANT_CODE, orderNo)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT payment_order_no FROM mall_order WHERE merchant_order_no = ?",
                    String.class, orderNo)).isEqualTo(paymentNo);
        } finally {
            jdbc.update("DELETE FROM mall_order_item WHERE order_id IN "
                    + "(SELECT id FROM mall_order WHERE merchant_order_no = ?)", orderNo);
            jdbc.update("DELETE FROM mall_order WHERE merchant_order_no = ?", orderNo);
            if (paymentNo != null) {
                jdbc.update("DELETE FROM mock_channel_trade WHERE payment_order_no = ?", paymentNo);
            }
            jdbc.update("DELETE FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?",
                    MallPaymentGateway.MERCHANT_CODE, orderNo);
        }
    }

    /** 关联写入前收到回调，三次投递耗尽后后台仍能恢复原交易。 */
    @Test
    void recoveryFindsPaidTradeAfterEarlyCallbackAttemptsExhausted() throws Exception {
        String body = mvc.perform(post("/api/mall/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productCode":"MALL-NOTEBOOK","quantity":1}]}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(body).path("merchantOrderNo").asText();
        try {
            mvc.perform(post("/api/mall/orders/{orderNo}/payment", orderNo))
                    .andExpect(status().isServiceUnavailable());
            String paymentNo = orders.findByMerchantOrderNo(MallPaymentGateway.MERCHANT_CODE, orderNo)
                    .orElseThrow().paymentOrderNo();
            String tradeNo = jdbc.queryForObject("SELECT channel_trade_no FROM mock_channel_trade WHERE payment_order_no = ?",
                    String.class, paymentNo);
            channel.complete(tradeNo, true).orElseThrow();
            for (int attempt = 1; attempt < 3; attempt++) {
                jdbc.update("UPDATE mock_channel_trade SET callback_due_at_utc = UTC_TIMESTAMP(3) WHERE channel_trade_no = ?",
                        tradeNo);
                channel.retryDueCallbacks();
            }
            assertThat(jdbc.queryForObject("SELECT callback_attempts FROM mock_channel_trade WHERE channel_trade_no = ?",
                    Integer.class, tradeNo)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT callback_due_at_utc FROM mock_channel_trade WHERE channel_trade_no = ?",
                    java.time.LocalDateTime.class, tradeNo)).isNull();
            assertThat(orders.findByPaymentOrderNo(MallPaymentGateway.MERCHANT_CODE, paymentNo))
                    .hasValueSatisfying(order -> assertThat(order.status()).isEqualTo("CREATED"));

            jobs.reconcileDue();
            assertThat(orders.findByPaymentOrderNo(MallPaymentGateway.MERCHANT_CODE, paymentNo))
                    .hasValueSatisfying(order -> assertThat(order.status()).isEqualTo("SUCCESS"));
            assertThat(jdbc.queryForObject("SELECT channel_trade_no FROM payment_order WHERE payment_order_no = ?",
                    String.class, paymentNo)).isEqualTo(tradeNo);
        } finally {
            jdbc.update("DELETE FROM mall_order_item WHERE order_id IN "
                    + "(SELECT id FROM mall_order WHERE merchant_order_no = ?)", orderNo);
            jdbc.update("DELETE FROM mall_order WHERE merchant_order_no = ?", orderNo);
            jdbc.update("""
                    DELETE FROM mock_channel_trade WHERE payment_order_no IN
                        (SELECT payment_order_no FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?)
                    """, MallPaymentGateway.MERCHANT_CODE, orderNo);
            jdbc.update("DELETE FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?",
                    MallPaymentGateway.MERCHANT_CODE, orderNo);
        }
    }

    /** 同时发起重试，并验证商城拿到原交易的付款链接。 */
    private Void retry(String orderNo, String paymentNo, String tradeNo, CountDownLatch ready) throws Exception {
        ready.countDown();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        mvc.perform(post("/api/mall/orders/{orderNo}/payment", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentResponse.paymentOrderNo").value(paymentNo))
                .andExpect(jsonPath("$.paymentResponse.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentResponse.paymentUrl").value("/mock-payments/" + tradeNo));
        return null;
    }
}

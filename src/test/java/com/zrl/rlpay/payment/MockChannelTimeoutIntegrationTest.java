package com.zrl.rlpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实数据库验证渠道已受理但商城收到 503 时的重试。 */
@SpringBootTest(properties = "rlpay.mock-channel.timeout-on-first-accept=true")
@AutoConfigureMockMvc
class MockChannelTimeoutIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentOrderService orders;

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

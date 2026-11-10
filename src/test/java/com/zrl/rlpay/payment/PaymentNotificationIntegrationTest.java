package com.zrl.rlpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.mall.service.MallOrderService;
import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.channel.MockPaymentChannel;
import com.zrl.rlpay.payment.service.PaymentNotificationService;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import com.zrl.rlpay.payment.service.PaymentResultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实 MySQL 验证支付确认、商城入账以及投递恢复。 */
@SpringBootTest(properties = "rlpay.payment.jobs-enabled=false")
@AutoConfigureMockMvc
class PaymentNotificationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MallOrderService mall;
    @Autowired private MallPaymentGateway gateway;
    @Autowired private PaymentOrderService payments;
    @Autowired private PaymentResultService results;
    @Autowired private PaymentNotificationService notifications;
    @Autowired private MockPaymentChannel channel;

    /** 投递记录写入失败时，支付成功更新必须一起回滚。 */
    @Test
    void successAndNotificationCommitAtomically() throws Exception {
        String orderNo = createMallOrder();
        try {
            var request = mall.freezeForPayment(orderNo);
            var response = gateway.createOrGet(request);
            String paymentNo = response.paymentOrderNo();
            channel.complete(tradeNo(response), false);
            jdbc.update("""
                    INSERT INTO payment_notification
                        (payment_order_no, event_type, merchant_code, merchant_order_no, amount_cent)
                    VALUES (?, 'PAYMENT_SUCCEEDED', ?, ?, ?)
                    """, paymentNo, request.merchantCode(), orderNo, request.amountCent());
            assertThatThrownBy(() -> results.query(paymentNo)).isInstanceOf(DuplicateKeyException.class);
            assertThat(payments.findByPaymentOrderNo(request.merchantCode(), paymentNo).orElseThrow().status())
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT paid_at_utc FROM payment_order WHERE payment_order_no = ?",
                    java.time.LocalDateTime.class, paymentNo)).isNull();
            jdbc.update("DELETE FROM payment_notification WHERE payment_order_no = ?", paymentNo);

            assertThat(results.query(paymentNo)).isEqualTo(PaymentResultService.Outcome.SUCCESS);
            assertThat(results.query(paymentNo)).isEqualTo(PaymentResultService.Outcome.SUCCESS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification WHERE payment_order_no = ?",
                    Integer.class, paymentNo)).isEqualTo(1);
        } finally {
            cleanup(orderNo);
        }
    }

    /** 首次失败退避，恢复后补写单号；确认丢失与迟到写回不会重复推进。 */
    @Test
    void failureAndLostAcknowledgementCanBeRetried() throws Exception {
        String orderNo = createMallOrder();
        try {
            var request = mall.freezeForPayment(orderNo);
            var response = gateway.createOrGet(request);
            String paymentNo = response.paymentOrderNo();
            channel.complete(tradeNo(response), true);
            assertThat(mall.getOrder(orderNo).paymentOrderNo()).isNull();

            jdbc.update("UPDATE payment_notification SET amount_cent = amount_cent + 1 WHERE payment_order_no = ?", paymentNo);
            notifications.deliverDue();
            assertThat(notificationStatus(paymentNo)).isEqualTo("PENDING");
            assertThat(attempts(paymentNo)).isEqualTo(1);
            assertThat(mall.getOrder(orderNo).status().name()).isEqualTo("PAYMENT_REQUESTED");
            assertThat(payments.findByPaymentOrderNo(request.merchantCode(), paymentNo).orElseThrow().status())
                    .isEqualTo("SUCCESS");
            jdbc.update("UPDATE payment_notification SET amount_cent = ? WHERE payment_order_no = ?",
                    request.amountCent(), paymentNo);
            notifications.deliverDue();
            assertThat(attempts(paymentNo)).isEqualTo(1);

            // 模拟商城事务已提交，工作进程在确认送达前退出。
            mall.confirmPayment(new MallOrderService.PaymentConfirmation(request.merchantCode(), orderNo,
                    paymentNo, request.amountCent()));
            var paid = mall.getOrder(orderNo);
            jdbc.update("""
                    UPDATE payment_notification SET claim_token = 'abandoned-worker',
                        next_attempt_at_utc = TIMESTAMPADD(SECOND, 60, UTC_TIMESTAMP(3))
                    WHERE payment_order_no = ?
                    """, paymentNo);
            notifications.deliverDue();
            assertThat(notificationStatus(paymentNo)).isEqualTo("PENDING");
            makeDue(paymentNo);
            notifications.deliverDue();
            assertThat(notificationStatus(paymentNo)).isEqualTo("DELIVERED");
            assertThat(attempts(paymentNo)).isEqualTo(2);

            mall.savePaymentOrderNo(orderNo, paymentNo);
            assertThat(mall.getOrder(orderNo).version()).isEqualTo(paid.version());
            assertThat(mall.getOrder(orderNo).status().name()).isEqualTo("PAID");
            assertThat(mall.getOrder(orderNo).paymentOrderNo()).isEqualTo(paymentNo);
        } finally {
            cleanup(orderNo);
        }
    }

    /** 通知失败到上限后保留原因，人工重投可恢复商城状态。 */
    @Test
    void exhaustedNotificationCanBeRequeued() throws Exception {
        String orderNo = createMallOrder();
        try {
            var request = mall.freezeForPayment(orderNo);
            var response = gateway.createOrGet(request);
            String paymentNo = response.paymentOrderNo();
            channel.complete(tradeNo(response), true);
            long id = jdbc.queryForObject("SELECT id FROM payment_notification WHERE payment_order_no = ?",
                    Long.class, paymentNo);
            jdbc.update("UPDATE payment_notification SET merchant_code = 'WRONG' WHERE id = ?", id);
            for (int attempt = 0; attempt < 5; attempt++) {
                makeDue(paymentNo);
                notifications.deliverDue();
            }
            assertThat(notificationStatus(paymentNo)).isEqualTo("FAILED");
            assertThat(attempts(paymentNo)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT last_error FROM payment_notification WHERE id = ?", String.class, id))
                    .contains("无效");
            jdbc.update("UPDATE payment_notification SET merchant_code = ? WHERE id = ?", request.merchantCode(), id);
            assertThat(notifications.retryFailed(id)).isTrue();
            notifications.deliverDue();
            assertThat(notificationStatus(paymentNo)).isEqualTo("DELIVERED");
            assertThat(mall.getOrder(orderNo).status().name()).isEqualTo("PAID");
            assertThat(notifications.retryFailed(id)).isFalse();
        } finally {
            cleanup(orderNo);
        }
    }

    /** 创建含有预置商品的商城订单。 */
    private String createMallOrder() throws Exception {
        String body = mvc.perform(post("/api/mall/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productCode":"MALL-NOTEBOOK","quantity":1}]}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("merchantOrderNo").asText();
    }

    /** 从付款链接提取模拟渠道交易号。 */
    private static String tradeNo(MallPaymentGateway.Response response) {
        return response.paymentUrl().substring(response.paymentUrl().lastIndexOf('/') + 1);
    }

    /** 查询当前的通知状态。 */
    private String notificationStatus(String paymentNo) {
        return jdbc.queryForObject("SELECT status FROM payment_notification WHERE payment_order_no = ?",
                String.class, paymentNo);
    }

    /** 查询已经确认的投递次数。 */
    private int attempts(String paymentNo) {
        return jdbc.queryForObject("SELECT attempts FROM payment_notification WHERE payment_order_no = ?",
                Integer.class, paymentNo);
    }

    /** 将测试通知的下次投递时间提前，避免等待真实退避。 */
    private void makeDue(String paymentNo) {
        jdbc.update("UPDATE payment_notification SET next_attempt_at_utc = UTC_TIMESTAMP(3) WHERE payment_order_no = ?",
                paymentNo);
    }

    /** 清理本测试生成的订单、渠道交易和通知。 */
    private void cleanup(String orderNo) {
        jdbc.update("DELETE FROM payment_notification WHERE merchant_code = ? AND merchant_order_no = ?",
                MallPaymentGateway.MERCHANT_CODE, orderNo);
        jdbc.update("""
                DELETE FROM mock_channel_trade WHERE payment_order_no IN
                    (SELECT payment_order_no FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?)
                """, MallPaymentGateway.MERCHANT_CODE, orderNo);
        jdbc.update("DELETE FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?",
                MallPaymentGateway.MERCHANT_CODE, orderNo);
        jdbc.update("DELETE FROM mall_order_item WHERE order_id IN "
                + "(SELECT id FROM mall_order WHERE merchant_order_no = ?)", orderNo);
        jdbc.update("DELETE FROM mall_order WHERE merchant_order_no = ?", orderNo);
    }
}

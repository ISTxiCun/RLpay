package com.zrl.rlpay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.mall.service.MallOrderService;
import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.service.PaymentAmountConflictException;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用 MySQL 和真实 Spring Bean 验证唯一约束、商户范围和商城交接。 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentOrderIntegrationTest {
    @Autowired private PaymentOrderService payments;
    @Autowired private MallOrderService mallOrders;
    @Autowired private MallPaymentGateway gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    /** 并发请求复用同一原单，金额冲突与跨商户查询走不同的处理路径。 */
    @Test
    void concurrentCreationAndMerchantIsolation() throws Exception {
        String orderNo = "IT-" + UUID.randomUUID();
        String firstMerchant = "IT-MERCHANT-A";
        String secondMerchant = "IT-MERCHANT-B";
        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<PaymentOrderService.OrderView> create = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发用例未能启动");
                }
                return payments.createOrGet(firstMerchant, orderNo, 1790);
            };
            var results = new ArrayList<Future<PaymentOrderService.OrderView>>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(create));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var original = results.get(0).get(15, TimeUnit.SECONDS);
            for (var result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(original);
            }
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?
                    """, Integer.class, firstMerchant, orderNo)).isEqualTo(1);
            assertThat(original.status()).isEqualTo("CREATED");
            assertThat(payments.findByPaymentOrderNo(firstMerchant, original.paymentOrderNo())).contains(original);
            assertThat(payments.findByMerchantOrderNo(firstMerchant, orderNo)).contains(original);
            assertThatThrownBy(() -> payments.createOrGet(firstMerchant, orderNo, 1791))
                    .isInstanceOf(PaymentAmountConflictException.class);
            assertThat(payments.findByMerchantOrderNo(firstMerchant, orderNo)).contains(original);

            assertThat(payments.findByMerchantOrderNo(secondMerchant, orderNo)).isEmpty();
            var other = payments.createOrGet(secondMerchant, orderNo, 1791);
            assertThat(other.paymentOrderNo()).isNotEqualTo(original.paymentOrderNo());
            assertThat(payments.findByPaymentOrderNo(secondMerchant, original.paymentOrderNo())).isEmpty();
            assertThat(payments.findByPaymentOrderNo(firstMerchant, other.paymentOrderNo())).isEmpty();
            assertThat(payments.findByMerchantOrderNo(secondMerchant, orderNo)).contains(other);
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(20, TimeUnit.SECONDS);
            jdbc.update("DELETE FROM payment_order WHERE merchant_order_no = ? AND merchant_code IN (?, ?)",
                    orderNo, firstMerchant, secondMerchant);
        }
    }

    /** 商城写回前请求中断时，重试通过 HTTP 入口找回原支付单并补写。 */
    @Test
    void mallRetryWritesBackPreviouslyCommittedPayment() throws Exception {
        String orderNo = createMallOrder();
        try {
            var request = mallOrders.freezeForPayment(orderNo);
            var original = gateway.createOrGet(request);
            assertThat(mallOrders.getOrder(orderNo).paymentOrderNo()).isNull();

            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(post("/api/mall/orders/{orderNo}/payment", orderNo))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.paymentResponse.paymentOrderNo").value(original.paymentOrderNo()))
                        .andExpect(jsonPath("$.paymentResponse.status").value("CREATED"));
            }
            mvc.perform(get("/api/mall/orders/{orderNo}", orderNo))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentOrderNo").value(original.paymentOrderNo()))
                    .andExpect(jsonPath("$.status").value("PAYMENT_REQUESTED"));
            assertThat(payments.findByMerchantOrderNo(MallPaymentGateway.MERCHANT_CODE, orderNo))
                    .hasValueSatisfying(order -> assertThat(order.amountCent()).isEqualTo(2580));
        } finally {
            deleteMallOrder(orderNo);
        }
    }

    /** 通过商城 HTTP 入口验证正常创建，以及金额冲突时不写回错误单号。 */
    @Test
    void mallHttpCreatesPaymentAndRejectsAmountConflict() throws Exception {
        String normal = createMallOrder();
        try {
            var result = mvc.perform(post("/api/mall/orders/{orderNo}/payment", normal))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentResponse.status").value("CREATED"))
                    .andReturn();
            String paymentNo = json.readTree(result.getResponse().getContentAsString())
                    .path("paymentResponse").path("paymentOrderNo").asText();
            assertThat(paymentNo).startsWith("PAY-");
            assertThat(mallOrders.getOrder(normal).paymentOrderNo()).isEqualTo(paymentNo);
        } finally {
            deleteMallOrder(normal);
        }

        String conflict = createMallOrder();
        try {
            var original = payments.createOrGet(MallPaymentGateway.MERCHANT_CODE, conflict, 1);
            mvc.perform(post("/api/mall/orders/{orderNo}/payment", conflict))
                    .andExpect(status().isConflict());
            assertThat(mallOrders.getOrder(conflict).paymentOrderNo()).isNull();
            assertThat(payments.findByMerchantOrderNo(MallPaymentGateway.MERCHANT_CODE, conflict))
                    .contains(original);
        } finally {
            deleteMallOrder(conflict);
        }
    }

    /** 数据库编码无法表达的标识不得进入查询，以免驱动替换字符后命中另一笔订单。 */
    @Test
    void malformedIdentifierCannotAliasExistingOrder() {
        String merchant = "IT-MERCHANT";
        String prefix = "IT-" + UUID.randomUUID();
        String real = prefix + "?";
        String malformed = prefix + (char) 0xD800;
        try {
            payments.createOrGet(merchant, real, 1790);
            assertThatThrownBy(() -> payments.createOrGet(merchant, malformed, 1790))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> payments.findByMerchantOrderNo(merchant, malformed))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            jdbc.update("DELETE FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?",
                    merchant, real);
        }
    }

    /** 用种子商品创建总金额固定的商城草稿订单。 */
    private String createMallOrder() throws Exception {
        String body = mvc.perform(post("/api/mall/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productCode":"MALL-NOTEBOOK","quantity":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmountCent").value(2580))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("merchantOrderNo").asText();
    }

    /** 清理此测试创建的商城明细、业务订单和支付单。 */
    private void deleteMallOrder(String orderNo) {
        jdbc.update("DELETE FROM mall_order_item WHERE order_id IN "
                + "(SELECT id FROM mall_order WHERE merchant_order_no = ?)", orderNo);
        jdbc.update("DELETE FROM mall_order WHERE merchant_order_no = ?", orderNo);
        jdbc.update("DELETE FROM payment_order WHERE merchant_code = ? AND merchant_order_no = ?",
                MallPaymentGateway.MERCHANT_CODE, orderNo);
    }

    /** 拒绝无效支付输入和伪装成其他商户的商城网关请求。 */
    @Test
    void invalidRequestsAreRejected() {
        assertThatThrownBy(() -> payments.createOrGet(" ", "IT-ORDER", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payments.createOrGet("IT-MERCHANT", "", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payments.createOrGet("IT-MERCHANT", "IT-ORDER", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.createOrGet(new MallPaymentGateway.Request("OTHER", "IT-ORDER", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

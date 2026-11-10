package com.zrl.rlpay.payment.stripe.impl;

import com.zrl.rlpay.payment.service.PaymentChannelService;
import com.zrl.rlpay.payment.service.PaymentChannelBinding;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import com.zrl.rlpay.payment.service.PaymentResultService;
import com.zrl.rlpay.payment.stripe.StripeCheckoutService;
import com.zrl.rlpay.payment.stripe.StripeCheckoutClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;


/** 将 Stripe 网络调用和本地关联提交分开；超时使用稳定幂等键重试。 */
@Service
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeCheckoutServiceImpl implements StripeCheckoutService {
    private final PaymentOrderService orders;
    private final PaymentChannelService channels;
    private final PaymentChannelBinding binding;
    private final PaymentResultService results;
    private final StripeCheckoutClient stripe;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** 仅关联写入需要平台事务。 */
    public StripeCheckoutServiceImpl(PaymentOrderService orders, PaymentChannelService channels,
            PaymentResultService results, StripeCheckoutClient stripe, PaymentChannelBinding binding,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.orders = orders;
        this.channels = channels;
        this.binding = binding;
        this.results = results;
        this.stripe = stripe;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 先记下幂等窗口，再发起渠道；超过窗口的未绑定单停止自动重试。 */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Launch start(String merchantCode, String paymentNo) {
        var order = orders.findByPaymentOrderNo(merchantCode, paymentNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("SUCCESS".equals(order.status()) || "EXPIRED".equals(order.status())) {
            return new Launch(paymentNo, order.status(), null);
        }
        if ("PENDING".equals(order.status())) {
            return pending(paymentNo, merchantCode);
        }
        if (!"CREATED".equals(order.status()) || order.amountCent() < 50 || order.amountCent() > 99_999_999L) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "平台订单状态或 USD 金额不支持 Stripe 测试发起");
        }
        jdbc.update("""
                INSERT INTO stripe_checkout_attempt (payment_order_no, requested_at_utc, next_recovery_at_utc, request_form)
                VALUES (?, UTC_TIMESTAMP(3), TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(3)), ?)
                ON DUPLICATE KEY UPDATE payment_order_no = stripe_checkout_attempt.payment_order_no
                """, paymentNo, stripe.requestForm(paymentNo, order.amountCent()));
        var attempt = jdbc.queryForObject("""
                SELECT requested_at_utc > UTC_TIMESTAMP(3) - INTERVAL 23 HOUR, request_form
                FROM stripe_checkout_attempt WHERE payment_order_no = ?
                """, (row, index) -> new Attempt(row.getBoolean(1), row.getString(2)), paymentNo);
        if (attempt == null || !attempt.insideWindow()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发起幂等窗口已过，需核实渠道后再处理");
        }

        if (attempt.form() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "历史发起缺少原请求参数，需人工核实");
        }
        StripeCheckoutClient.Checkout checkout;
        try {
            checkout = stripe.create(paymentNo, order.amountCent(), attempt.form());
        } catch (org.springframework.web.client.RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe 测试渠道暂未确认受理");
        }
        try {
            transaction.executeWithoutResult(tx -> {
                binding.bind(merchantCode, paymentNo, StripeCheckoutService.CHANNEL_CODE, checkout.tradeNo());
                jdbc.update("UPDATE stripe_checkout_attempt SET checkout_url = ? WHERE payment_order_no = ?",
                        checkout.url(), paymentNo);
            });
        } catch (PaymentChannelBinding.Conflict conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
        return pending(paymentNo, merchantCode);
    }

    /** 未关联或结果未确认时返回失败，由 Stripe 重投或后台继续查单。 */
    @Override
    public boolean confirm(String channelTradeNo) {
        return channels.findByChannelTradeNo(StripeCheckoutService.CHANNEL_CODE, channelTradeNo)
                .map(order -> {
                    var outcome = results.query(order.paymentOrderNo());
                    return outcome == PaymentResultService.Outcome.SUCCESS
                            || outcome == PaymentResultService.Outcome.EXPIRED;
                })
                .orElse(false);
    }

    /** 从持久化状态返回原链接，已确认成功时不再给出链接。 */
    private Launch pending(String paymentNo, String merchantCode) {
        var launches = jdbc.query("""
                SELECT p.status, a.checkout_url
                FROM payment_order p LEFT JOIN stripe_checkout_attempt a ON a.payment_order_no = p.payment_order_no
                WHERE p.payment_order_no = ? AND p.merchant_code = ?
                """, (row, index) -> new Launch(paymentNo, row.getString(1), row.getString(2)), paymentNo, merchantCode);
        var launch = launches.stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("SUCCESS".equals(launch.status()) || "EXPIRED".equals(launch.status())) {
            return new Launch(paymentNo, launch.status(), null);
        }
        if (!"PENDING".equals(launch.status()) || launch.paymentUrl() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "平台支付单没有 Stripe 付款链接");
        }
        return launch;
    }
    private record Attempt(boolean insideWindow, String form) {
    }
}

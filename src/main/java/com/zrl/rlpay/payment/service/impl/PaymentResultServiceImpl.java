package com.zrl.rlpay.payment.service.impl;

import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.channel.ChannelResultProvider;
import com.zrl.rlpay.payment.service.PaymentResultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 渠道回调和主动查单共用一处状态校验和迁移。 */
@Service
@Slf4j
public class PaymentResultServiceImpl implements PaymentResultService {
    private final JdbcTemplate jdbc;
    private final Map<String, ChannelResultProvider> channels;
    private final TransactionTemplate transactions;

    /** 每次确认在独立事务中完成，结束后再向渠道确认接收结果。 */
    public PaymentResultServiceImpl(JdbcTemplate jdbc, List<ChannelResultProvider> channels, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.channels = channels.stream().collect(Collectors.toUnmodifiableMap(
                ChannelResultProvider::channelCode, Function.identity()));
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 仅由进程内渠道事件调用，并在写入前校验其内容。 */
    @Override
    public Outcome acceptResult(ChannelResultProvider.Result callback) {
        if (callback == null || callback.channelCode() == null || !channels.containsKey(callback.channelCode())
                || callback.channelTradeNo() == null || callback.channelTradeNo().isBlank()
                || callback.channelTradeNo().length() > 255 || callback.amountCent() <= 0
                || !("SUCCESS".equals(callback.status()) && callback.paidAt() != null
                    || "PENDING".equals(callback.status()) && callback.paidAt() == null)) {
            log.warn("拒绝无效的渠道结果");
            return Outcome.REJECTED;
        }
        return transactions.execute(transaction -> apply(callback));
    }

    /** 查单时只使用已持久化的渠道关联；渠道查询发生在状态写入事务之外。 */
    @Override
    public Outcome query(String paymentOrderNo) {
        var reference = jdbc.query("""
                SELECT channel_code, channel_trade_no, status FROM payment_order WHERE payment_order_no = ?
                """, (row, index) -> new Reference(row.getString(1), row.getString(2), row.getString(3)), paymentOrderNo)
                .stream().findFirst();
        if (reference.isEmpty()) {
            return Outcome.REJECTED;
        }
        var order = reference.orElseThrow();
        if ("SUCCESS".equals(order.status())) {
            return Outcome.SUCCESS;
        }
        var channel = order.channel() == null ? null : channels.get(order.channel());
        if (channel == null || order.tradeNo() == null) {
            return Outcome.RETRY;
        }
        return channel.queryResult(order.tradeNo())
                .map(this::acceptResult)
                .orElse(Outcome.RETRY);
    }

    /** 行锁串行核对结果，条件更新保证同一支付单只确认一次。 */
    private Outcome apply(ChannelResultProvider.Result callback) {
        var orders = jdbc.query("""
                SELECT id, amount_cent, status, payment_order_no, merchant_code, merchant_order_no FROM payment_order
                WHERE channel_code = ? AND channel_trade_no = ? FOR UPDATE
                """, (row, index) -> new LocalOrder(row.getLong(1), row.getLong(2), row.getString(3),
                        row.getString(4), row.getString(5), row.getString(6)),
                callback.channelCode(), callback.channelTradeNo());
        if (orders.isEmpty()) {
            return Outcome.RETRY;
        }
        var order = orders.get(0);
        if (order.amountCent() != callback.amountCent()) {
            log.warn("渠道结果金额不一致 tradeNo={}", callback.channelTradeNo());
            return Outcome.REJECTED;
        }
        if ("SUCCESS".equals(order.status())) {
            return Outcome.SUCCESS;
        }
        if (!"PENDING".equals(order.status())) {
            return Outcome.RETRY;
        }
        if ("PENDING".equals(callback.status())) {
            return Outcome.PENDING;
        }
        int updated = jdbc.update("""
                UPDATE payment_order SET status = 'SUCCESS', paid_at_utc = ?, next_check_at_utc = NULL
                WHERE id = ? AND status = 'PENDING'
                """, LocalDateTime.ofInstant(callback.paidAt(), ZoneOffset.UTC), order.id());
        if (updated != 1) {
            throw new IllegalStateException("确认支付状态失败");
        }
        if (MallPaymentGateway.MERCHANT_CODE.equals(order.merchantCode())) {
            jdbc.update("""
                INSERT INTO payment_notification
                    (payment_order_no, event_type, merchant_code, merchant_order_no, amount_cent, next_attempt_at_utc)
                VALUES (?, 'PAYMENT_SUCCEEDED', ?, ?, ?, UTC_TIMESTAMP(3))
                """, order.paymentNo(), order.merchantCode(), order.merchantOrderNo(), order.amountCent());
        }
        return Outcome.SUCCESS;
    }

    private record Reference(String channel, String tradeNo, String status) {
    }

    private record LocalOrder(long id, long amountCent, String status, String paymentNo,
                              String merchantCode, String merchantOrderNo) {
    }
}

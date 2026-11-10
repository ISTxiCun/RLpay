package com.zrl.rlpay.payment.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/** 模拟渠道的持久化交易、查单和成功事件。 */
@Service
public class MockPaymentChannel {
    public static final String CODE = "MOCK";
    public static final String PAYMENT_PATH = "/mock-payments";

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final boolean timeoutOnFirstAccept;

    /** 通过独立事务模拟渠道先受理，再返回响应或超时。 */
    public MockPaymentChannel(JdbcTemplate jdbc, ApplicationEventPublisher events,
                              PlatformTransactionManager manager,
                              @Value("${rlpay.mock-channel.timeout-on-first-accept:false}") boolean timeoutOnFirstAccept) {
        this.jdbc = jdbc;
        this.events = events;
        this.timeoutOnFirstAccept = timeoutOnFirstAccept;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 按平台支付单号只受理一次，重试时返回原渠道交易及链接。 */
    public StartResult start(String paymentOrderNo, long amountCent) {
        String tradeNo = "MOCK-" + UUID.randomUUID();
        try {
            transactions.executeWithoutResult(transaction -> jdbc.update("""
                    INSERT INTO mock_channel_trade (channel_trade_no, payment_order_no, amount_cent, status)
                    VALUES (?, ?, ?, 'PENDING')
                    """, tradeNo, paymentOrderNo, amountCent));
        } catch (DuplicateKeyException duplicate) {
            Trade existing = transactions.execute(transaction -> findBy("payment_order_no", paymentOrderNo)
                    .orElseThrow(() -> duplicate));
            if (existing.amountCent() != amountCent) {
                throw new IllegalStateException("模拟渠道的幂等请求金额不一致");
            }
            return new StartResult(existing.channelTradeNo(), paymentUrl(existing.channelTradeNo()));
        }
        if (timeoutOnFirstAccept) {
            throw new TransientDataAccessResourceException("模拟渠道已受理，首次应答超时");
        }
        return new StartResult(tradeNo, paymentUrl(tradeNo));
    }

    /** 按渠道交易号查看渠道状态、金额及首次成功时间。 */
    public Optional<Trade> query(String channelTradeNo) {
        return findBy("channel_trade_no", channelTradeNo);
    }

    /** 模拟付款成功；只在首次更新提交后按选项发出渠道成功事件。 */
    public Optional<Trade> complete(String channelTradeNo, boolean sendCallback) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int changed = transactions.execute(transaction -> jdbc.update("""
                UPDATE mock_channel_trade SET status = 'SUCCESS', paid_at_utc = ?
                WHERE channel_trade_no = ? AND status = 'PENDING'
                """, LocalDateTime.ofInstant(now, ZoneOffset.UTC), channelTradeNo));
        Optional<Trade> trade = transactions.execute(transaction -> query(channelTradeNo));
        if (sendCallback && changed == 1) {
            Trade result = trade.orElseThrow();
            events.publishEvent(new Callback(CODE, result.channelTradeNo(), result.amountCent(),
                    result.status(), result.paidAt()));
        }
        return trade;
    }

    /** 返回供商城浏览器打开的模拟渠道付款路径。 */
    public static String paymentUrl(String channelTradeNo) {
        return PAYMENT_PATH + "/" + channelTradeNo;
    }

    /** 按内部选定的唯一键读取渠道交易，查询值始终作为参数绑定。 */
    private Optional<Trade> findBy(String column, String value) {
        return jdbc.query("""
                SELECT channel_trade_no, amount_cent, status, paid_at_utc FROM mock_channel_trade WHERE
                """ + column + " = ?", (row, index) -> {
            LocalDateTime paidAt = row.getObject("paid_at_utc", LocalDateTime.class);
            return new Trade(row.getString("channel_trade_no"), row.getLong("amount_cent"),
                    row.getString("status"), paidAt == null ? null : paidAt.toInstant(ZoneOffset.UTC));
        }, value).stream().findFirst();
    }

    /** 渠道接受发起后返回的数据。 */
    public record StartResult(String channelTradeNo, String paymentUrl) {
    }

    /** 渠道查单结果。 */
    public record Trade(String channelTradeNo, long amountCent, String status, Instant paidAt) {
    }

    /** 模拟渠道成功事件，供第 4 步消费。 */
    public record Callback(String channelCode, String channelTradeNo, long amountCent, String status, Instant paidAt) {
    }
}

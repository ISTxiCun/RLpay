package com.zrl.rlpay.payment.job;

import com.zrl.rlpay.payment.channel.MockPaymentChannel;
import com.zrl.rlpay.payment.service.PaymentChannelService;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import com.zrl.rlpay.payment.service.PaymentResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时重投模拟回调，恢复已受理的发起请求，并补查待支付单。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentResultJobs {
    private final JdbcTemplate jdbc;
    private final MockPaymentChannel channel;
    private final PaymentChannelService channels;
    private final PaymentResultService results;

    /** 进程内回调由模拟渠道发送；缺少关联时要求渠道稍后重投。 */
    @EventListener
    public void onCallback(MockPaymentChannel.Callback callback) {
        if (results.acceptCallback(callback) == PaymentResultService.Outcome.RETRY) {
            throw new IllegalStateException("渠道关联尚未就绪，稍后重投");
        }
    }

    /** 每轮从数据库取到期记录，重启后可继续。 */
    @Scheduled(fixedDelayString = "${rlpay.payment.result-job-delay-ms:5000}")
    public void reconcileDue() {
        channel.retryDueCallbacks();
        reconcile("CREATED");
        reconcile("PENDING");
    }

    /** 分开扫描两种状态，已创建单只恢复渠道已经受理的交易。 */
    private void reconcile(String status) {
        var due = jdbc.query("""
                SELECT p.id, p.payment_order_no, p.merchant_code, p.merchant_order_no, p.amount_cent
                FROM payment_order p
                WHERE p.status = ? AND (p.next_check_at_utc IS NULL OR p.next_check_at_utc <= UTC_TIMESTAMP(3))
                  AND ((p.status = 'PENDING' AND p.channel_code IS NOT NULL AND p.channel_trade_no IS NOT NULL)
                    OR (p.status = 'CREATED' AND EXISTS (
                        SELECT 1 FROM mock_channel_trade m WHERE m.payment_order_no = p.payment_order_no)))
                ORDER BY p.next_check_at_utc, p.id LIMIT 50
                """, (row, index) -> new Candidate(row.getLong(1),
                        new PaymentOrderService.OrderView(row.getString(2), row.getString(3), row.getString(4),
                                row.getLong(5), status)), status);
        for (var item : due) {
            try {
                if (jdbc.update("""
                        UPDATE payment_order
                        SET next_check_at_utc = TIMESTAMPADD(SECOND, 5 * (1 << LEAST(result_checks, 6)), UTC_TIMESTAMP(3)),
                            result_checks = LEAST(result_checks + 1, 6)
                        WHERE id = ? AND status = ?
                          AND (next_check_at_utc IS NULL OR next_check_at_utc <= UTC_TIMESTAMP(3))
                        """, item.id(), status) == 0) {
                    continue;
                }
                if ("CREATED".equals(status)) {
                    channels.launch(item.order());
                }
                results.query(item.order().paymentOrderNo());
            } catch (RuntimeException failure) {
                log.warn("支付补查暂未完成 paymentOrderNo={}", item.order().paymentOrderNo(), failure);
            }
        }
    }

    private record Candidate(long id, PaymentOrderService.OrderView order) {
    }
}

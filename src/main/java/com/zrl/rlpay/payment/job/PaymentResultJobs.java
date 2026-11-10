package com.zrl.rlpay.payment.job;

import com.zrl.rlpay.payment.channel.ChannelLaunchRecovery;

import java.util.List;
import com.zrl.rlpay.payment.service.PaymentResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时重投模拟回调，恢复已受理的发起请求，并补查待支付单。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentResultJobs {
    private final JdbcTemplate jdbc;
    private final List<ChannelLaunchRecovery> recovery;
    private final PaymentResultService results;

    /** 每轮从数据库取到期记录，重启后可继续。 */
    @Scheduled(fixedDelayString = "${rlpay.payment.result-job-delay-ms:5000}")
    public void reconcileDue() {
        for (var strategy : recovery) {
            try {
                strategy.recoverDue();
            } catch (RuntimeException failure) {
                log.warn("渠道恢复任务未完成", failure);
            }
        }
        reconcilePending();
    }

    /** 只补查已绑定的支付单，具体渠道表由各渠道的恢复组件维护。 */
    private void reconcilePending() {
        var due = jdbc.query("""
                SELECT id, payment_order_no FROM payment_order
                WHERE status = 'PENDING' AND channel_code IS NOT NULL AND channel_trade_no IS NOT NULL
                  AND (next_check_at_utc IS NULL OR next_check_at_utc <= UTC_TIMESTAMP(3))
                ORDER BY next_check_at_utc, id LIMIT 50
                """, (row, index) -> new Candidate(row.getLong(1), row.getString(2)));
        for (var item : due) {
            try {
                if (jdbc.update("""
                        UPDATE payment_order
                        SET next_check_at_utc = TIMESTAMPADD(SECOND, 5 * (1 << LEAST(result_checks, 6)), UTC_TIMESTAMP(3)),
                            result_checks = LEAST(result_checks + 1, 6)
                        WHERE id = ? AND status = 'PENDING'
                          AND (next_check_at_utc IS NULL OR next_check_at_utc <= UTC_TIMESTAMP(3))
                        """, item.id()) == 0) {
                    continue;
                }
                results.query(item.paymentNo());
            } catch (RuntimeException failure) {
                log.warn("支付补查暂未完成 paymentOrderNo={}", item.paymentNo(), failure);
            }
        }
    }

    private record Candidate(long id, String paymentNo) {
    }
}

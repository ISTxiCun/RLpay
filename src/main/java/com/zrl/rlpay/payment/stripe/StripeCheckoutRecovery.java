package com.zrl.rlpay.payment.stripe;

import com.zrl.rlpay.payment.channel.ChannelLaunchRecovery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 从已提交的 Stripe 发起记录恢复关联，每轮最多发起一次外部调用。 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeCheckoutRecovery implements ChannelLaunchRecovery {
    private final JdbcTemplate jdbc;
    private final StripeCheckoutService checkout;

    /** 原子领取到期请求，进程中断后可按持久化时间重新领取。 */
    @Override
    public void recoverDue() {
        var candidates = jdbc.query("""
                SELECT a.payment_order_no, p.merchant_code
                FROM stripe_checkout_attempt a JOIN payment_order p ON p.payment_order_no = a.payment_order_no
                WHERE p.status = 'CREATED' AND a.checkout_url IS NULL
                  AND a.requested_at_utc > UTC_TIMESTAMP(3) - INTERVAL 23 HOUR
                  AND (a.next_recovery_at_utc IS NULL OR a.next_recovery_at_utc <= UTC_TIMESTAMP(3))
                ORDER BY a.next_recovery_at_utc, a.payment_order_no LIMIT 1
                """, (row, index) -> new Candidate(row.getString(1), row.getString(2)));
        for (var candidate : candidates) {
            try {
                if (jdbc.update("""
                        UPDATE stripe_checkout_attempt
                        SET next_recovery_at_utc = TIMESTAMPADD(SECOND, 30 * (1 << LEAST(recovery_attempts, 6)), UTC_TIMESTAMP(3)),
                            recovery_attempts = LEAST(recovery_attempts + 1, 6)
                        WHERE payment_order_no = ? AND checkout_url IS NULL
                          AND requested_at_utc > UTC_TIMESTAMP(3) - INTERVAL 23 HOUR
                          AND (next_recovery_at_utc IS NULL OR next_recovery_at_utc <= UTC_TIMESTAMP(3))
                        """, candidate.paymentNo()) == 1) {
                    checkout.start(candidate.merchant(), candidate.paymentNo());
                }
            } catch (RuntimeException failure) {
                log.warn("Stripe 关联暂未恢复 paymentOrderNo={} reason={}", candidate.paymentNo(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    private record Candidate(String paymentNo, String merchant) {
    }
}

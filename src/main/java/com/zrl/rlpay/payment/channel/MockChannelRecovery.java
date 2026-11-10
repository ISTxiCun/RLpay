package com.zrl.rlpay.payment.channel;

import com.zrl.rlpay.payment.service.PaymentChannelService;
import com.zrl.rlpay.payment.service.PaymentOrderService.OrderView;
import com.zrl.rlpay.payment.service.PaymentResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 模拟渠道知道自己的受理记录，负责重投和补齐关联。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MockChannelRecovery implements ChannelLaunchRecovery {
    private final JdbcTemplate jdbc;
    private final MockPaymentChannel channel;
    private final PaymentChannelService channels;
    private final PaymentResultService results;

    /** 先重投到期回调，再恢复模拟渠道已受理的发起请求。 */
    @Override
    public void recoverDue() {
        channel.retryDueCallbacks();
        var orders = jdbc.query("""
                SELECT p.id, p.payment_order_no, p.merchant_code, p.merchant_order_no, p.amount_cent
                FROM payment_order p WHERE p.status = 'CREATED'
                  AND (p.next_check_at_utc IS NULL OR p.next_check_at_utc <= UTC_TIMESTAMP(3))
                  AND EXISTS (SELECT 1 FROM mock_channel_trade m WHERE m.payment_order_no = p.payment_order_no)
                ORDER BY p.next_check_at_utc, p.id LIMIT 50
                """, (row, index) -> new Candidate(row.getLong(1), new OrderView(row.getString(2),
                        row.getString(3), row.getString(4), row.getLong(5), "CREATED")));
        for (var item : orders) {
            try {
                if (jdbc.update("""
                        UPDATE payment_order
                        SET next_check_at_utc = TIMESTAMPADD(SECOND, 5 * (1 << LEAST(result_checks, 6)), UTC_TIMESTAMP(3)),
                            result_checks = LEAST(result_checks + 1, 6)
                        WHERE id = ? AND status = 'CREATED'
                          AND (next_check_at_utc IS NULL OR next_check_at_utc <= UTC_TIMESTAMP(3))
                        """, item.id()) == 1) {
                    channels.launch(item.order());
                    results.query(item.order().paymentOrderNo());
                }
            } catch (RuntimeException failure) {
                log.warn("模拟渠道关联暂未恢复 paymentOrderNo={}", item.order().paymentOrderNo(), failure);
            }
        }
    }

    private record Candidate(long id, OrderView order) {
    }
}

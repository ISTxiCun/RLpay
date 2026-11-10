package com.zrl.rlpay.payment.service.impl;

import com.zrl.rlpay.mall.service.MallOrderService;
import com.zrl.rlpay.payment.service.PaymentNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 通过数据库租约投递支付成功通知，确认丢失后允许重复发送。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentNotificationServiceImpl implements PaymentNotificationService {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final MallOrderService mallOrders;

    /** 平台投递进度与商城处理各自提交，避免跨模块持有数据库锁。 */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliverDue() {
        var ids = jdbc.queryForList("""
                SELECT id FROM payment_notification WHERE status = 'PENDING'
                  AND next_attempt_at_utc <= UTC_TIMESTAMP(3)
                ORDER BY next_attempt_at_utc, id LIMIT 50
                """, Long.class);
        for (long id : ids) {
            try {
                deliver(id);
            } catch (RuntimeException failure) {
                log.warn("通知投递进度暂未写入，将在租约到期后重试 id={}", id, failure);
            }
        }
    }

    /** 只重置终止失败的通知，保留最近一次失败原因直到后续结果覆盖。 */
    @Override
    public boolean retryFailed(long notificationId) {
        return jdbc.update("""
                UPDATE payment_notification SET status = 'PENDING', attempts = 0,
                    next_attempt_at_utc = UTC_TIMESTAMP(3), claim_token = NULL
                WHERE id = ? AND status = 'FAILED'
                """, notificationId) == 1;
    }

    /** 先领取再调用商城，旧领取者不得写入较新领取者的结果。 */
    private void deliver(long id) {
        String token = UUID.randomUUID().toString();
        if (jdbc.update("""
                UPDATE payment_notification SET claim_token = ?,
                    next_attempt_at_utc = TIMESTAMPADD(SECOND, 30, UTC_TIMESTAMP(3))
                WHERE id = ? AND status = 'PENDING' AND next_attempt_at_utc <= UTC_TIMESTAMP(3)
                """, token, id) == 0) {
            return;
        }
        var payloads = jdbc.query("""
                SELECT merchant_code, merchant_order_no, payment_order_no, amount_cent
                FROM payment_notification WHERE id = ? AND claim_token = ?
                """, (row, index) -> new MallOrderService.PaymentConfirmation(
                row.getString(1), row.getString(2), row.getString(3), row.getLong(4)), id, token);
        if (payloads.isEmpty()) {
            return;
        }
        try {
            mallOrders.confirmPayment(payloads.get(0));
        } catch (RuntimeException failure) {
            recordFailure(id, token, failure.getMessage());
            return;
        }
        jdbc.update("""
                UPDATE payment_notification SET status = 'DELIVERED', attempts = attempts + 1,
                    next_attempt_at_utc = NULL, claim_token = NULL, last_error = NULL
                WHERE id = ? AND claim_token = ?
                """, id, token);
    }

    /** 已确认的失败按 5、10、20、40 秒退避，第五次失败保留供人工重投。 */
    private void recordFailure(long id, String token, String reason) {
        jdbc.update("""
                UPDATE payment_notification SET
                    status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at_utc = CASE WHEN attempts + 1 >= ? THEN NULL
                        ELSE TIMESTAMPADD(SECOND, 5 * (1 << attempts), UTC_TIMESTAMP(3)) END,
                    attempts = attempts + 1, claim_token = NULL, last_error = LEFT(?, 500)
                WHERE id = ? AND claim_token = ?
                """, MAX_ATTEMPTS, MAX_ATTEMPTS, reason == null ? "商城未确认通知" : reason, id, token);
    }
}

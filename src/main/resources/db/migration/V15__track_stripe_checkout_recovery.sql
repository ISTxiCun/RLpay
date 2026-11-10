-- 保存 Stripe 发起恢复进度。历史未绑定的请求也可在幂等窗口内重新领取。
ALTER TABLE stripe_checkout_attempt
    ADD COLUMN next_recovery_at_utc DATETIME(3) NULL,
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
    ADD INDEX ix_stripe_checkout_recovery (next_recovery_at_utc, payment_order_no);

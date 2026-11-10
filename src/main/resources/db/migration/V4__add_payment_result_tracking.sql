-- 平台支付单的首次成功时间与可恢复补查进度，时间字段均为 UTC。
ALTER TABLE payment_order
    ADD COLUMN paid_at_utc DATETIME(3) NULL,
    ADD COLUMN next_check_at_utc DATETIME(3) NULL,
    ADD COLUMN result_checks INT NOT NULL DEFAULT 0,
    ADD INDEX ix_payment_result_due (status, next_check_at_utc, id);

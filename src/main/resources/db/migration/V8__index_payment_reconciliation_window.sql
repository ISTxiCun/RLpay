-- 支持按渠道、成功状态和 UTC 账单窗口分批读取平台支付单。
ALTER TABLE payment_order
    ADD INDEX ix_payment_reconciliation (channel_code, status, paid_at_utc, id);

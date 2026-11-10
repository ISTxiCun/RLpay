-- 平台支付单的渠道关联。存量 CREATED 单保留空关联，首次发起后补齐。
ALTER TABLE payment_order
    ADD COLUMN channel_code VARCHAR(32) NULL,
    ADD COLUMN channel_trade_no VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_payment_channel_trade (channel_code, channel_trade_no);

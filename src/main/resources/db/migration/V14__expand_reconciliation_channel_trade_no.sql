-- 对账差异沿用平台所支持的渠道交易号长度。
ALTER TABLE reconciliation_difference MODIFY COLUMN channel_trade_no VARCHAR(255) NOT NULL;

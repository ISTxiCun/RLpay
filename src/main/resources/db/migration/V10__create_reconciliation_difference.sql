-- 每个账单日、渠道、交易和差异类型保留一份原始证据及后续处理结果。
CREATE TABLE reconciliation_difference (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id CHAR(36) NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    bill_date DATE NOT NULL,
    channel_trade_no VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    bill_snapshot JSON NULL,
    platform_snapshot JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result VARCHAR(256) NULL,
    payment_order_no VARCHAR(64) NULL,
    created_at_utc DATETIME(3) NOT NULL,
    checked_at_utc DATETIME(3) NULL,
    UNIQUE KEY uk_recon_difference (channel_code, bill_date, channel_trade_no, reason),
    KEY ix_recon_difference_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 平台支付单的渠道关联。存量 CREATED 单保留空关联，首次发起后补齐。
ALTER TABLE payment_order
    ADD COLUMN channel_code VARCHAR(32) NULL,
    ADD COLUMN channel_trade_no VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_payment_channel_trade (channel_code, channel_trade_no);

-- 模拟渠道维护自己的交易。平台支付单号是发起请求的幂等键。
CREATE TABLE mock_channel_trade (
    channel_trade_no VARCHAR(64) NOT NULL,
    payment_order_no VARCHAR(64) NOT NULL,
    amount_cent BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    paid_at_utc DATETIME(3) NULL,
    PRIMARY KEY (channel_trade_no),
    UNIQUE KEY uk_mock_channel_payment_order (payment_order_no),
    CHECK (amount_cent > 0),
    CHECK (status IN ('PENDING', 'SUCCESS')),
    CHECK ((status = 'SUCCESS') = (paid_at_utc IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

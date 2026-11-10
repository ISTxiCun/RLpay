-- 平台支付成功通知。投递状态独立于支付单状态，租约到期可由其他进程接手。
CREATE TABLE payment_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_order_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    merchant_code VARCHAR(64) NOT NULL,
    merchant_order_no VARCHAR(128) NOT NULL,
    amount_cent BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at_utc DATETIME(3) NULL,
    claim_token VARCHAR(36) NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_notification_event (payment_order_no, event_type),
    KEY ix_payment_notification_due (status, next_attempt_at_utc, id),
    CHECK (amount_cent > 0),
    CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

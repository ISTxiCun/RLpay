-- 平台支付单。一个商户订单对应一条记录，金额以整数分保存。
CREATE TABLE payment_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_order_no VARCHAR(64) NOT NULL,
    merchant_code VARCHAR(64) NOT NULL,
    merchant_order_no VARCHAR(128) NOT NULL,
    amount_cent BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_no (payment_order_no),
    UNIQUE KEY uk_payment_merchant_order (merchant_code, merchant_order_no),
    CHECK (amount_cent > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- 独立记录 Stripe 测试渠道发起时间和已绑定的付款链接，避免超时后重复建单。
CREATE TABLE stripe_checkout_attempt (
    payment_order_no VARCHAR(64) NOT NULL PRIMARY KEY,
    requested_at_utc DATETIME(3) NOT NULL,
    checkout_url VARCHAR(2048) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

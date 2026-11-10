-- Stripe 测试 Checkout 会话号超过模拟渠道的 64 字符限制。
ALTER TABLE payment_order MODIFY COLUMN channel_trade_no VARCHAR(255) NULL;

-- 模拟渠道的回调重投进度。空的下次投递时间表示无需投递。
ALTER TABLE mock_channel_trade
    ADD COLUMN callback_due_at_utc DATETIME(3) NULL,
    ADD COLUMN callback_attempts INT NOT NULL DEFAULT 0,
    ADD INDEX ix_mock_callback_due (callback_due_at_utc, channel_trade_no);

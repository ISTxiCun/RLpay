-- 对账导入批次的元数据。逐笔差异的持久化属于下一阶段。
CREATE TABLE reconciliation_batch (
    batch_id CHAR(36) NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    bill_date DATE NOT NULL,
    bill_zone VARCHAR(64) NOT NULL,
    bill_row_count INT NOT NULL,
    imported_at_utc DATETIME(3) NOT NULL,
    PRIMARY KEY (batch_id),
    CHECK (bill_row_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

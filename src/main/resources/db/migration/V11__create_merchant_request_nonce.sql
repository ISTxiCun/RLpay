-- 已验签请求的防重放标识，实例重启后仍有效，到期后分批删除。
CREATE TABLE merchant_request_nonce (
    credential_id VARCHAR(64) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    expires_at_epoch BIGINT NOT NULL,
    PRIMARY KEY (credential_id, nonce),
    KEY ix_merchant_nonce_expiry (expires_at_epoch)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

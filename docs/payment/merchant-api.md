# 商户支付单 API

默认关闭。通过受控环境配置启用，示例中第一个凭证使用索引 0，其他凭证使用后续索引。

| 环境变量 | 含义 |
| --- | --- |
| `RLPAY_MERCHANT_API_ENABLED` | 设为 true 启用 |
| `RLPAY_MERCHANT_API_CREDENTIALS_0_ID` | 凭证 ID |
| `RLPAY_MERCHANT_API_CREDENTIALS_0_MERCHANTCODE` | 可信商户号 |
| `RLPAY_MERCHANT_API_CREDENTIALS_0_SECRET` | 至少 32 随机字节的 Base64 值 |
| `RLPAY_MERCHANT_API_CREDENTIALS_0_ENABLED` | 设为 true 启用该凭证 |

请求走 HTTPS。签名串由以下六行以 LF 拼接，末尾无换行。用解码后的凭证密钥做 HMAC-SHA256，输出小写十六进制，放入 `X-Signature`。

1. HTTP 方法（大写）。
2. 发送的原始 URL 路径，不含域名或查询参数。
3. `X-Key-Id`。
4. `X-Timestamp`，Unix 秒，允许与服务端相差 300 秒。
5. `X-Nonce`，每次请求新生成，16–64 个字母、数字、下划线或连字符。
6. 请求体原始字节的 SHA-256 小写十六进制；GET 使用空字节串。

| 入口 | 行为 |
| --- | --- |
| `POST /api/merchant/payments` | JSON 仅含 `merchantOrderNo` 与正整数 `amountCent`，创建或复用平台支付单 |
| `GET /api/merchant/payments/{paymentOrderNo}` | 按平台单号查询 |
| `GET /api/merchant/payments/by-order/{merchantOrderNo}` | 按当前商户的业务订单号查询 |

同一业务订单重试使用新 nonce，商户号从验签凭证取得。跨商户与不存在的订单均返回 404，金额不一致返回 409，签名不匹配、过期或重放返回 401。此阶段的外部建单只生成平台支付单，渠道交易由后续支付流程发起。

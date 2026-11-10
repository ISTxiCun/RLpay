# Stripe 测试渠道联调

状态：第 9 步首段的本地实现，真实沙箱验收待完成。仅支持 Stripe Checkout 测试模式的 USD 卡支付。应用默认关闭该渠道，禁止使用生产密钥。

## 配置

- 启用第 8 步商户 API 和一个测试商户凭证。
- 将 `RLPAY_STRIPE_SANDBOX_ENABLED` 设为 true。
- 通过受控环境配置 `RLPAY_STRIPE_TEST_SECRET_KEY`（`sk_test_` 或 `rk_test_`）和 `RLPAY_STRIPE_WEBHOOK_SECRET`（`whsec_`）。
- 配置 `RLPAY_STRIPE_SANDBOX_SUCCESS_URL`、`RLPAY_STRIPE_SANDBOX_CANCEL_URL` 为测试站点的 HTTPS 地址。
- 在 Stripe 测试环境将 `checkout.session.completed` 发送到应用的 `/api/payment/webhooks/stripe-test`。Webhook 验签使用该入口对应的密钥。

## 验收顺序

1. 通过商户 API 创建 USD 测试支付单。用相同签名规则对空请求体签名，调用 `POST /api/merchant/payments/{paymentNo}/stripe-checkout`。
2. 打开 Checkout 链接，以 Stripe 官方测试卡完成付款，验证回调推动平台单到 `SUCCESS`。暂停回调后另建一单，验证后台主动查单。
3. 模拟渠道受理后丢失响应，用同一平台支付单号和新的商户 nonce 重试。Stripe 创建使用稳定幂等键，平台保留首次发起时间，23 小时后仍无关联的订单拒绝继续发起，需人工核实。
4. 在受控环境启用内部对账入口，调用 `GET /internal/reconciliation/stripe-test/bill?billDate=YYYY-MM-DD&billZone=Asia/Shanghai` 下载测试卡支付 CSV，以渠道 `STRIPE_TEST` 导入。账单由 Stripe Charge API 的成功交易生成，使用 Checkout 会话号进行匹配。
5. 首段通过真实沙箱后，继续关单、独立退款单、退款账单和最终验收。

参考：[Stripe API 密钥](https://docs.stripe.com/keys)、[Checkout Session 创建](https://docs.stripe.com/api/checkout/sessions/create)、[Webhook 验签](https://docs.stripe.com/webhooks)、[卡交易列表](https://docs.stripe.com/api/charges/list)。

# Stripe 测试渠道验收

本指南验收第 9 步的首段：Stripe Checkout 发起、回调验签、主动查单和支付账单对账。首段使用 USD 卡支付，Stripe 渠道默认关闭，仅接受测试密钥。关单、退款和退款对账有独立验收范围。

## 不用 Stripe 账户：本地自动验收

准备独立 MySQL 测试库，按[项目的测试库说明](../mall/mall-api-design.md#本地运行与初始化)配置数据库。用 Java 17 执行：

```bash
mvn -Dtest=StripeSandboxClientTest,StripeCheckoutIntegrationTest test
```

这两组测试共 5 项，通过时应看到 0 failures、0 errors。HTTP 替身验证发起请求、签名验签、查单解析和支付账单映射；MySQL 测试验证超时后复用平台单、提前回调可重试，以及支付成功只推进一次。端到端测试还验证了商户 HTTP 发起、无效与有效的 Webhook 签名、以及查询到 SUCCESS。这些测试不连接 Stripe。

## 用真实 Stripe 测试模式手动验收

### 1. 准备应用和回调

1. 准备 Java 17、Maven、MySQL 测试库、Python 3 和 [Stripe CLI](https://docs.stripe.com/stripe-cli/install)。应用数据库使用 `RLPAY_DB_URL`、`RLPAY_DB_USERNAME`、`RLPAY_DB_PASSWORD`；已有商城表但没有 Flyway 历史表时，按[数据库启动说明](../mall/mall-api-design.md#本地运行与初始化)先配置基线。
2. 按[商户 API 文档](merchant-api.md)启用第 8 步入口，并配置一个启用的测试凭证。用独立测试商户号，例如 `STRIPE_DEMO`。保持商户密钥与 Stripe API 密钥分开。
3. 用 Stripe CLI 登录与 API 测试密钥相同的测试账户，另开一个终端运行监听。这个进程转发回调到本机。

   ```bash
   stripe login
   stripe listen --events checkout.session.completed --forward-to http://127.0.0.1:8080/api/payment/webhooks/stripe-test
   ```

4. 在应用进程的环境中设置下表。使用 CLI 转发时，Webhook 签名密钥使用本次监听输出的 `whsec_`；改用 Stripe Dashboard 中配置的公开 HTTPS 端点时，用该端点自己的签名密钥。

   | 环境变量 | 设置 |
   | --- | --- |
   | `RLPAY_STRIPE_SANDBOX_ENABLED` | `true` |
   | `RLPAY_STRIPE_TEST_SECRET_KEY` | 同一账户的 `sk_test_` 或有相应权限的 `rk_test_` |
   | `RLPAY_STRIPE_WEBHOOK_SECRET` | 当前监听或端点的 `whsec_` |
   | `RLPAY_STRIPE_SANDBOX_SUCCESS_URL` | 测试用 HTTPS 返回地址 |
   | `RLPAY_STRIPE_SANDBOX_CANCEL_URL` | 测试用 HTTPS 取消地址 |
   | `RLPAY_RECONCILIATION_IMPORT_ENABLED` | `true`，仅在受控联调环境用于账单验收 |
   | `RLPAY_PAYMENT_RESULT_JOB_DELAY_MS` | 验证回调时可暂设为 `300000`，便于与主动查单区分 |

   从项目根目录用 `mvn spring-boot:run` 启动应用。下文默认端口 8080，若不同则同时调整 CLI 转发地址和脚本的 `RLPAY_SMOKE_BASE_URL`。

### 2. 验证发起、复用和回调

在运行脚本的终端保留商户凭证的 `RLPAY_MERCHANT_API_CREDENTIALS_0_ID` 与 `RLPAY_MERCHANT_API_CREDENTIALS_0_SECRET`。订单号 `stripe-demo-001` 每次完整演练用新的值。

```bash
python docs/payment/stripe-smoke.py create stripe-demo-001
python docs/payment/stripe-smoke.py start PAY-替换为上一步返回的单号
python docs/payment/stripe-smoke.py start PAY-替换为同一个单号
```

建单预期 `CREATED`。两次发起均应返回 `PENDING`、相同平台单号和相同 Checkout 链接。打开链接，使用 [Stripe 测试卡](https://docs.stripe.com/testing)付款，例如 `4242 4242 4242 4242`，到期日填任意未来日期、CVC 填任意三位数字。记录监听终端中的 `checkout.session.completed` 和应用返回的 HTTP 204，然后查询平台单：

```bash
python docs/payment/stripe-smoke.py query PAY-替换为同一个单号
```

预期 `SUCCESS`。若再发起，预期仍返回原平台单号与 `SUCCESS`，`paymentUrl` 为空。重复发起可验证复用；已受理但应答丢失的故障注入由本地自动测试覆盖。

### 3. 验证主动查单和账单

1. 将补查间隔恢复默认后重启应用，停止 Stripe CLI 监听，并确认没有其他向本应用投递回调的测试端点。另建一单并付款。查单接口应先看到 `PENDING` 或已补查完成的 `SUCCESS`；等待后台任务，最终应为 `SUCCESS`。
2. 用付款实际发生日替换 `YYYY-MM-DD`，账单时区保持一致。从 Stripe 测试交易生成 CSV，再导入 RLpay。Windows PowerShell 可把命令中的 `curl` 换成 `curl.exe`。

   ```bash
   curl --fail-with-body "http://127.0.0.1:8080/internal/reconciliation/stripe-test/bill?billDate=YYYY-MM-DD&billZone=Asia/Shanghai" -o stripe-test.csv
   curl --fail-with-body http://127.0.0.1:8080/internal/reconciliation/bills -F "channelCode=STRIPE_TEST" -F "billDate=YYYY-MM-DD" -F "billZone=Asia/Shanghai" -F "file=@stripe-test.csv;type=text/csv"
   ```

   预期逐笔结果中，本次已成功的测试交易为 `MATCHED`，平台单号、渠道会话号和金额一致。若导入为空或有差异，先核对 Stripe 账户、付款发生日、时区和本地支付状态。保留订单号、会话号、回调 HTTP 状态和对账分类即可，验收记录无需包含密钥。

官方资料：[CLI 转发回调](https://docs.stripe.com/stripe-cli/use-cli)、[API 密钥](https://docs.stripe.com/keys)、[Webhook 验签](https://docs.stripe.com/webhooks)、[卡交易列表](https://docs.stripe.com/api/charges/list)。

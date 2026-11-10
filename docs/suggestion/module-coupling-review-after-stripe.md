# RLpay 耦合与内聚复审

生成时间：11月11日 03:45（北京时间）
查阅标记：第 1、2 项已批准并实施；第 3 项保留触发条件
审查基线：main，a71c6cca6a9c674c7853214d24e7c449c910d6f0
范围：主分支业务源码与迁移。未做 Stripe 沙箱和负载验证。

## 判断

保留模块化单体和现有事务边界。对账模块已经通过支付只读快照读取支付数据，差异处理复用支付结果接口。现阶段最值得调整的是渠道发起与恢复、以及 Stripe 客户端职责。把这两处处理好再加入关单和退款，避免渠道越多，支付状态的写入规则越分散。

## 1. （已批准）优先统一渠道关联和恢复

- [模拟渠道发起](../../src/main/java/com/zrl/rlpay/payment/service/impl/PaymentChannelServiceImpl.java)和 [Stripe 发起](../../src/main/java/com/zrl/rlpay/payment/stripe/impl/StripeCheckoutServiceImpl.java)分别决定何时把平台单由 CREATED 改为 PENDING。前者用 MyBatis-Plus，后者直接更新同一张支付表。以后增加关单状态或修改绑定条件时，两处都要跟着改。
- [补查任务](../../src/main/java/com/zrl/rlpay/payment/job/PaymentResultJobs.java)的 PENDING 查询已能按渠道路由，但 CREATED 恢复仍直接检查 `mock_channel_trade`。如果 Stripe 已创建会话，应用在关联提交前中断且商户未重试，当前没有对应的后台恢复入口。Stripe 回调也需要先找到本地渠道关联，回调重投本身不能补绑定。这是从代码读到的恢复缺口，尚未在沙箱复现。
- 建议让支付侧只有一个渠道关联命令，统一校验旧状态、渠道和交易号。Stripe 付款链接和绑定仍在同一个本地事务保存，网络调用留在事务外。恢复策略由各渠道实现，Stripe 超过现有幂等窗口后应进入人工核实，避免再次创建会话。
- 验收覆盖关联并发、Stripe 已受理但未绑定后重启、提前回调，以及超过幂等窗口不重复创建。现有模拟渠道的超时恢复测试继续保留。

## 2. （已批准）在新增退款前拆分 Stripe 客户端职责

[审查时的 StripeSandboxClient](https://github.com/ISTxiCun/RLpay/blob/a71c6cca6a9c674c7853214d24e7c449c910d6f0/src/main/java/com/zrl/rlpay/payment/stripe/StripeSandboxClient.java)同时负责 Checkout 发起和查询、Webhook 原始字节验签、卡交易分页以及对账 CSV 生成。它们的变更原因分别是渠道 API、签名契约和对账格式。继续把关单与退款加进这一类，会让协议变更和对账变更落在同一测试与维护范围。

建议拆为 Checkout API、Webhook 验签与账单导出三个组件，共用受控的 HTTP 配置和必要的 Stripe 响应校验。支付结果服务接口已经有通用结果对象，可以把模拟事件到该对象的转换移到模拟渠道的事件入口，去掉接口对具体模拟类的依赖。验收时分别测试发起、验签和账单，再保留既有的端到端模拟测试。

## 3. 通知路由和调度按需要收敛

- [支付结果服务](../../src/main/java/com/zrl/rlpay/payment/service/impl/PaymentResultServiceImpl.java)知道 `MALL_MOCK` 才写商城通知。[通知服务](../../src/main/java/com/zrl/rlpay/payment/service/impl/PaymentNotificationServiceImpl.java)依赖整个商城订单服务，实际只调用支付确认。目前只有一个接收方，先保留。开放第二种通知接收方时，再引入明确的通知路由和较窄的确认接口。继续在支付成功事务内保存需要的通知，商城用独立事务确认。
- Stripe 已参与后台 PENDING 补查。补查与商城通知没有显式分配独立调度器，慢渠道调用可能推迟其他任务。启用外部渠道联调时，应观察通知积压与补查耗时；有可复现互相拖延时再隔离调度，并验证并发领取与幂等。
- 商户建单和 Stripe 发起两处各自提取签名头，但共用实际验签和防重放。增加关单或退款接口时，可顺手收敛头提取与空请求体验证。Webhook 仍保留自己的签名方式。

## 建议执行范围

先做一个小 PR 收敛支付关联写入并补 Stripe 恢复验证，再拆分 Stripe 客户端职责。商城、对账模块和数据库部署形态可以继续沿用。MyBatis-Plus 与 JdbcTemplate 共存不是问题，重点是相同支付状态规则只由一个边界维护。


## 执行记录

- PR #10 收敛渠道绑定，模拟渠道和 Stripe 各自恢复发起记录，保留 Stripe 链接与关联的同事务提交。
- Stripe 职责拆分后，Checkout、Webhook 和账单各有入口，复用原 HTTP 配置。模拟回调转换移入模拟渠道入口。

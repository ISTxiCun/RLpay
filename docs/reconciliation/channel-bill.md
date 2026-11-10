# 模拟渠道账单导入与对账

导入记录批次、返回逐笔报告，并保存差异快照。导入不会修改支付单或商城订单。

## 文件格式

CSV 使用 UTF-8，可带 BOM。固定五列，字段可整体加双引号；字段内不支持逗号、引号或换行。表头必须存在，只有表头时视为空账单，仍执行反向缺单检查。每份账单最多 1 MiB、10000 行交易，每个报告最多 20000 笔。空行、非法金额、重复交易号、渠道不符和超出账单日的交易会拒绝整份文件，并给出物理行号。

```csv
channel_code,channel_trade_no,amount_cent,status,paid_at
MOCK,MOCK-sample-001,1290,SUCCESS,2026-11-11T00:30:00+08:00
```

交易号允许字母、数字、点、下划线和连字符，最多 64 字符。金额为正整数分，状态为 `SUCCESS`，支付时间使用包含 UTC 偏移的 ISO-8601 格式。示例属于 `Asia/Shanghai` 的 2026-11-11 账单。

## 本地联调

在本机测试环境设置 `RLPAY_RECONCILIATION_IMPORT_ENABLED=true` 后启动应用。导入入口默认关闭，适用于受控联调环境。将示例保存为 `mock.csv`，可用以下命令导入。

```bash
curl --fail-with-body http://127.0.0.1:8080/internal/reconciliation/bills \
  -F 'channelCode=MOCK' -F 'billDate=2026-11-11' -F 'billZone=Asia/Shanghai' \
  -F 'file=@mock.csv;type=text/csv'
```

成功响应包含批次号、账单日、时区、UTC 窗口、每类数量与逐笔明细。内容错误返回 HTTP 400，响应中的 `line` 和 `reason` 指出位置和原因。批次参数或文件整体错误的 `line` 为空。如果文件在容器上传限制处被拒绝，会收到 HTTP 413。若指定时区历史上跳过了该日期，整个批次会被拒绝。相同文件重跑会生成新的导入批次。差异记录按渠道、账单日、交易号和类型去重，重跑保留原证据和处理结果。

| 分类 | 含义 |
| --- | --- |
| `MATCHED` | 双方金额及状态一致 |
| `CHANNEL_ONLY` | 账单有单，本平台查不到同渠道交易 |
| `PLATFORM_ONLY` | 平台有同渠道、同账单日的成功单，账单无该交易 |
| `AMOUNT_MISMATCH` | 金额不一致 |
| `STATUS_MISMATCH` | 状态不一致，例如渠道成功而平台待支付 |

同笔交易可同时计入金额与状态差异。反向只读取成功单，按账单时区的当天零点（含）到次日零点（不含）转换成 UTC，支持夏令时日期。对账通过支付模块的只读快照读取本地支付单。


## 差异查询与处理

- `GET /internal/reconciliation/differences?afterId=0&status=PENDING` 返回最多 100 条，状态可省略。
- `GET /internal/reconciliation/differences/{id}` 查看原始证据与处理结果。
- `POST /internal/reconciliation/differences/{id}/process` 复用主动查单核实。状态差异确认成功后记为 `RESOLVED`，商城通知独立投递。金额冲突、缺单或未确认保留 `PENDING`。

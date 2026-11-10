package com.zrl.rlpay.payment.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

/** 支付模块向对账提供的只读快照。 */
public interface PaymentReconciliationReader {
    /** 同一渠道按交易号批量查询，包含任意平台状态。 */
    Map<String, Snapshot> findByTradeNos(String channelCode, Collection<String> tradeNos);

    /** 按渠道和 UTC 半开区间分批遍历平台成功单。 */
    void forEachSuccess(String channelCode, Instant fromInclusive, Instant toExclusive, Consumer<Snapshot> consumer);

    /** 对账所需的最小支付快照。 */
    record Snapshot(String channelTradeNo, String paymentOrderNo, long amountCent, String status, Instant paidAt) {
    }
}

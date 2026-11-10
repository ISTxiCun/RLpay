package com.zrl.rlpay.reconciliation.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 持久化对账证据，并核实可安全修复的状态差异。 */
public interface ReconciliationDifferenceService {
    /** 在导入事务中保存差异，重跑保留首次证据。 */
    void record(BillReconciliationService.Report report);

    /** 按主键分页列出差异，状态可选。 */
    List<Difference> list(long afterId, String status);

    /** 查看差异及原始证据。 */
    Optional<Difference> find(long id);

    /** 核实支付结果并记录处理状态。 */
    Optional<Difference> process(long id);

    record Difference(long id, String batchId, String channelCode, LocalDate billDate, String channelTradeNo,
                      String reason, BillReconciliationService.BillEntry bill,
                      BillReconciliationService.PlatformEntry platform, String status, String result,
                      String paymentOrderNo, Instant createdAt, Instant checkedAt) {
    }
}

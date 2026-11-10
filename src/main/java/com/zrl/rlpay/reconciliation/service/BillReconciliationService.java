package com.zrl.rlpay.reconciliation.service;

import com.zrl.rlpay.payment.service.PaymentReconciliationReader;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 导入一份渠道账单并返回逐笔对账报告。 */
public interface BillReconciliationService {
    /** 校验文件、生成报告并记录有效导入批次。 */
    Report reconcile(String channelCode, LocalDate billDate, String billZone, byte[] csv);

    enum Reason {
        MATCHED, CHANNEL_ONLY, PLATFORM_ONLY, AMOUNT_MISMATCH, STATUS_MISMATCH
    }

    /** 渠道账单行，仅保存成功交易。 */
    record BillEntry(int line, String channelTradeNo, long amountCent, String status, Instant paidAt) {
    }

    /** 两侧交易快照与本笔所有差异。缺单时对应侧为空。 */
    record Entry(String channelTradeNo, BillEntry bill, PaymentReconciliationReader.Snapshot platform,
                 List<Reason> reasons) {
    }

    /** 包含账单时区、UTC 比较窗口、分类计数与逐笔结果。 */
    record Report(String batchId, String channelCode, LocalDate billDate, String billZone,
                  Instant fromInclusive, Instant toExclusive, int billRowCount,
                  Map<Reason, Long> counts, List<Entry> entries) {
    }
}

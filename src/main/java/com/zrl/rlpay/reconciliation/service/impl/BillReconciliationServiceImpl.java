package com.zrl.rlpay.reconciliation.service.impl;

import com.zrl.rlpay.payment.service.PaymentReconciliationReader;
import com.zrl.rlpay.reconciliation.service.BillReconciliationService;
import com.zrl.rlpay.reconciliation.service.BillValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在同一个数据库快照中完成正向和反向匹配。 */
@Service
@RequiredArgsConstructor
public class BillReconciliationServiceImpl implements BillReconciliationService {
    private static final int MAX_REPORT_ROWS = 20_000;
    private final PaymentReconciliationReader payments;
    private final JdbcTemplate jdbc;
    private final BillCsvParser parser = new BillCsvParser();

    /** 账单先完整校验，之后比较两个方向并记录批次。 */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Report reconcile(String channelCode, LocalDate billDate, String billZone, byte[] csv) {
        ZoneId zone = validateMetadata(channelCode, billDate, billZone);
        var from = billDate.atStartOfDay(zone).toInstant();
        var to = billDate.plusDays(1).atStartOfDay(zone).toInstant();
        var billRows = parser.parse(csv, channelCode, from, to);

        var platformRows = payments.findByTradeNos(channelCode, billRows.keySet());
        List<Entry> entries = new ArrayList<>();
        Map<Reason, Long> counts = new EnumMap<>(Reason.class);
        for (Reason reason : Reason.values()) {
            counts.put(reason, 0L);
        }
        billRows.values().forEach(bill -> {
            var platform = platformRows.get(bill.channelTradeNo());
            append(entries, counts, new Entry(bill.channelTradeNo(), bill, platform, compare(bill, platform)));
        });
        payments.forEachSuccess(channelCode, from, to, platform -> {
            if (!billRows.containsKey(platform.channelTradeNo())) {
                append(entries, counts, new Entry(platform.channelTradeNo(), null, platform, List.of(Reason.PLATFORM_ONLY)));
            }
        });

        String batchId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO reconciliation_batch
                    (batch_id, channel_code, bill_date, bill_zone, bill_row_count, imported_at_utc)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                """, batchId, channelCode, billDate, zone.getId(), billRows.size());
        return new Report(batchId, channelCode, billDate, zone.getId(), from, to, billRows.size(),
                Map.copyOf(counts), List.copyOf(entries));
    }

    /** 校验批次标识及可落库日期，使用指定时区计算账单窗口。 */
    private static ZoneId validateMetadata(String channel, LocalDate date, String zone) {
        if (channel == null || !BillCsvParser.CHANNEL_CODE.matcher(channel).matches()) {
            throw new BillValidationException(null, "批次渠道格式错误");
        }
        if (date == null || date.getYear() < 1001 || date.getYear() > 9998) {
            throw new BillValidationException(null, "账单日期超出支持范围");
        }
        if (zone != null && zone.length() <= 64) {
            try {
                return ZoneId.of(zone);
            } catch (DateTimeException invalid) {
                // 无效时区统一按批次参数错误返回。
            }
        }
        throw new BillValidationException(null, "账单时区无效");
    }

    /** 比较同一笔账单的金额和状态，保留同时存在的差异。 */
    private static List<Reason> compare(BillEntry bill, PaymentReconciliationReader.Snapshot platform) {
        if (platform == null) {
            return List.of(Reason.CHANNEL_ONLY);
        }
        List<Reason> reasons = new ArrayList<>(2);
        if (bill.amountCent() != platform.amountCent()) {
            reasons.add(Reason.AMOUNT_MISMATCH);
        }
        if (!bill.status().equals(platform.status())) {
            reasons.add(Reason.STATUS_MISMATCH);
        }
        return reasons.isEmpty() ? List.of(Reason.MATCHED) : List.copyOf(reasons);
    }

    /** 记录逐笔结果和分类计数，超过模拟环境上限时拒绝整个批次。 */
    private static void append(List<Entry> entries, Map<Reason, Long> counts, Entry entry) {
        if (entries.size() >= MAX_REPORT_ROWS) {
            throw new BillValidationException(null, "逐笔报告超过 20000 条，无法导入该批次");
        }
        entries.add(entry);
        entry.reasons().forEach(reason -> counts.merge(reason, 1L, Long::sum));
    }
}

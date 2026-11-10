package com.zrl.rlpay.reconciliation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.payment.service.PaymentReconciliationReader;
import com.zrl.rlpay.payment.service.PaymentResultService;
import com.zrl.rlpay.reconciliation.service.BillReconciliationService;
import com.zrl.rlpay.reconciliation.service.BillValidationException;
import com.zrl.rlpay.reconciliation.service.ReconciliationDifferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 独立保存对账证据；主动核实复用支付状态服务。 */
@Service
@RequiredArgsConstructor
public class ReconciliationDifferenceServiceImpl implements ReconciliationDifferenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PaymentReconciliationReader payments;
    private final PaymentResultService results;

    /** 批量保存差异，唯一键冲突时保留已有记录。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(BillReconciliationService.Report report) {
        var rows = new ArrayList<Object[]>();
        for (var entry : report.entries()) {
            for (var reason : entry.reasons()) {
                if (reason != BillReconciliationService.Reason.MATCHED) {
                    rows.add(new Object[]{report.batchId(), report.channelCode(), report.billDate(),
                            entry.channelTradeNo(), reason.name(), encode(entry.bill()), encode(entry.platform())});
                }
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO reconciliation_difference
                        (batch_id, channel_code, bill_date, channel_trade_no, reason,
                         bill_snapshot, platform_snapshot, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3))
                    ON DUPLICATE KEY UPDATE id = reconciliation_difference.id
                    """, rows);
        }
    }

    /** 返回最多 100 条记录，后续传最后一条 ID 继续查询。 */
    @Override
    public List<Difference> list(long afterId, String status) {
        if (afterId < 0 || (status != null && !List.of("PENDING", "RESOLVED").contains(status))) {
            throw new BillValidationException(null, "差异列表参数无效");
        }
        return status == null
                ? jdbc.query("SELECT * FROM reconciliation_difference WHERE id > ? ORDER BY id LIMIT 100",
                        (row, index) -> read(row), afterId)
                : jdbc.query("SELECT * FROM reconciliation_difference WHERE status = ? AND id > ? ORDER BY id LIMIT 100",
                        (row, index) -> read(row), status, afterId);
    }

    /** 按 ID 查询一份原始证据及处理结果。 */
    @Override
    public Optional<Difference> find(long id) {
        return jdbc.query("SELECT * FROM reconciliation_difference WHERE id = ?", (row, index) -> read(row), id)
                .stream().findFirst();
    }

    /** 处理时不锁住差异记录等待查单；支付迁移幂等，已完成的差异不会被覆盖。 */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<Difference> process(long id) {
        var found = find(id);
        if (found.isEmpty() || "RESOLVED".equals(found.orElseThrow().status())) {
            return found;
        }
        var difference = found.orElseThrow();
        var result = verify(difference);
        jdbc.update("""
                UPDATE reconciliation_difference SET status = ?, result = ?, payment_order_no = ?,
                    checked_at_utc = UTC_TIMESTAMP(3)
                WHERE id = ? AND status = 'PENDING'
                """, result.resolved() ? "RESOLVED" : "PENDING", result.message(), result.paymentNo(), id);
        return find(id);
    }

    /** 只在证据、平台和渠道确认金额一致时处理成功状态差异。 */
    private Decision verify(Difference difference) {
        var bill = difference.bill();
        var original = difference.platform();
        if (!"STATUS_MISMATCH".equals(difference.reason()) || bill == null || original == null
                || !"SUCCESS".equals(bill.status()) || !"PENDING".equals(original.status())) {
            return new Decision(false, "需要人工核实差异", null);
        }
        if (bill.amountCent() != original.amountCent()) {
            return new Decision(false, "账单与平台金额不一致", original.paymentOrderNo());
        }
        try {
            var latest = payments.findByTradeNos(difference.channelCode(), List.of(difference.channelTradeNo()))
                    .get(difference.channelTradeNo());
            if (latest == null || !original.paymentOrderNo().equals(latest.paymentOrderNo())
                    || bill.amountCent() != latest.amountCent()) {
                return new Decision(false, "平台交易或金额已变化，需核实", original.paymentOrderNo());
            }
            var outcome = results.query(latest.paymentOrderNo());
            return outcome == PaymentResultService.Outcome.SUCCESS
                    ? new Decision(true, "平台已确认成功；商城通知状态独立查询", latest.paymentOrderNo())
                    : new Decision(false, "渠道结果未能确认：" + outcome.name(), latest.paymentOrderNo());
        } catch (DataAccessException unavailable) {
            return new Decision(false, "查单暂不可用，稍后重试", original.paymentOrderNo());
        }
    }

    /** 还原差异记录与两侧快照。 */
    private Difference read(ResultSet row) throws SQLException {
        return new Difference(row.getLong("id"), row.getString("batch_id"), row.getString("channel_code"),
                row.getObject("bill_date", LocalDate.class), row.getString("channel_trade_no"), row.getString("reason"),
                decode(row.getString("bill_snapshot"), BillReconciliationService.BillEntry.class),
                decode(row.getString("platform_snapshot"), BillReconciliationService.PlatformEntry.class),
                row.getString("status"), row.getString("result"), row.getString("payment_order_no"),
                utc(row, "created_at_utc"), utc(row, "checked_at_utc"));
    }

    /** 转换数据库 UTC 时间。 */
    private static Instant utc(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** 使用 JSON 保存原始快照。 */
    private String encode(Object value) {
        try {
            return value == null ? null : json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("差异证据无法保存", invalid);
        }
    }

    /** 将证据 JSON 还原成原始报告对象。 */
    private <T> T decode(String value, Class<T> type) {
        try {
            return value == null ? null : json.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("差异证据无法读取", invalid);
        }
    }

    private record Decision(boolean resolved, String message, String paymentNo) {
    }
}

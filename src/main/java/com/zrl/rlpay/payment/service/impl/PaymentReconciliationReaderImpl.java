package com.zrl.rlpay.payment.service.impl;

import com.zrl.rlpay.payment.service.PaymentReconciliationReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 将支付表结构和分页细节封装在支付模块内。 */
@Service
@RequiredArgsConstructor
public class PaymentReconciliationReaderImpl implements PaymentReconciliationReader {
    private static final int PAGE_SIZE = 500;
    private final JdbcTemplate jdbc;

    /** 对交易号分组查询，避免按账单行发起 SQL。 */
    @Override
    public Map<String, Snapshot> findByTradeNos(String channelCode, Collection<String> tradeNos) {
        var keys = new ArrayList<>(tradeNos);
        Map<String, Snapshot> result = new HashMap<>();
        for (int offset = 0; offset < keys.size(); offset += PAGE_SIZE) {
            var batch = keys.subList(offset, Math.min(offset + PAGE_SIZE, keys.size()));
            var parameters = new ArrayList<String>(batch.size() + 1);
            parameters.add(channelCode);
            parameters.addAll(batch);
            String marks = String.join(",", Collections.nCopies(batch.size(), "?"));
            jdbc.query("""
                    SELECT channel_trade_no, payment_order_no, amount_cent, status, paid_at_utc
                    FROM payment_order WHERE channel_code = ? AND channel_trade_no IN (
                    """ + marks + ")", (row, index) -> read(row), parameters.toArray())
                    .forEach(snapshot -> result.put(snapshot.channelTradeNo(), snapshot));
        }
        return result;
    }

    /** 以成功时间及主键作游标，相同毫秒的订单也能稳定分页。 */
    @Override
    public void forEachSuccess(String channelCode, Instant fromInclusive, Instant toExclusive,
                               Consumer<Snapshot> consumer) {
        LocalDateTime from = LocalDateTime.ofInstant(fromInclusive, ZoneOffset.UTC);
        LocalDateTime to = LocalDateTime.ofInstant(toExclusive, ZoneOffset.UTC);
        LocalDateTime cursorTime = from;
        long cursorId = 0;
        while (true) {
            var page = jdbc.query("""
                    SELECT id, channel_trade_no, payment_order_no, amount_cent, status, paid_at_utc
                    FROM payment_order
                    WHERE channel_code = ? AND status = 'SUCCESS' AND channel_trade_no IS NOT NULL
                      AND paid_at_utc >= ? AND paid_at_utc < ?
                      AND (paid_at_utc > ? OR (paid_at_utc = ? AND id > ?))
                    ORDER BY paid_at_utc, id LIMIT ?
                    """, (row, index) -> new CursorRow(row.getLong("id"), read(row)),
                    channelCode, from, to, cursorTime, cursorTime, cursorId, PAGE_SIZE);
            for (var row : page) {
                consumer.accept(row.snapshot());
            }
            if (page.size() < PAGE_SIZE) {
                return;
            }
            var last = page.get(page.size() - 1);
            cursorTime = LocalDateTime.ofInstant(last.snapshot().paidAt(), ZoneOffset.UTC);
            cursorId = last.id();
        }
    }

    /** 将数据库 UTC 时间转换成对账契约中的时间点。 */
    private static Snapshot read(ResultSet row) throws SQLException {
        var paidAt = row.getObject("paid_at_utc", LocalDateTime.class);
        return new Snapshot(row.getString("channel_trade_no"), row.getString("payment_order_no"),
                row.getLong("amount_cent"), row.getString("status"),
                paidAt == null ? null : paidAt.toInstant(ZoneOffset.UTC));
    }

    private record CursorRow(long id, Snapshot snapshot) {
    }
}

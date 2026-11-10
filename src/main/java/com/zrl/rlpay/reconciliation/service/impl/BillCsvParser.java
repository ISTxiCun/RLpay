package com.zrl.rlpay.reconciliation.service.impl;

import com.zrl.rlpay.reconciliation.service.BillReconciliationService;
import com.zrl.rlpay.reconciliation.service.BillReconciliationService.BillEntry;
import com.zrl.rlpay.reconciliation.service.BillValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 解析本项目的固定五列账单格式，错误定位到物理行。 */
final class BillCsvParser {
    private static final int MAX_ROWS = 10_000;
    static final Pattern CHANNEL_CODE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern TRADE_NO = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern CENTS = Pattern.compile("[0-9]+");
    private static final List<String> HEADER = List.of(
            "channel_code", "channel_trade_no", "amount_cent", "status", "paid_at");

    /** 解析并校验所有行；同一渠道批次内的交易号必须唯一。 */
    Map<String, BillEntry> parse(byte[] csv, String channel, Instant from, Instant to) {
        if (csv == null || csv.length == 0 || csv.length > BillReconciliationService.MAX_FILE_BYTES) {
            throw new BillValidationException(null, "CSV 必须非空且不超过 1 MiB");
        }
        String contents = new String(csv, StandardCharsets.UTF_8);
        var lines = contents.lines().iterator();
        String header = lines.next();
        if (header.startsWith("\uFEFF")) {
            header = header.substring(1);
        }
        if (!HEADER.equals(Arrays.asList(fields(header, 1)))) {
            throw new BillValidationException(1, "CSV 表头不符合约定");
        }
        Map<String, BillEntry> rows = new LinkedHashMap<>();
        int line = 1;
        while (lines.hasNext()) {
            line++;
            if (rows.size() >= MAX_ROWS) {
                throw new BillValidationException(line, "每份 CSV 最多 10000 条交易");
            }
            var columns = fields(lines.next(), line);
            if (!channel.equals(columns[0])) {
                throw new BillValidationException(line, "行内渠道与批次渠道不一致");
            }
            String tradeNo = columns[1];
            if (!TRADE_NO.matcher(tradeNo).matches()) {
                throw new BillValidationException(line, "渠道交易号格式错误");
            }
            if (rows.containsKey(tradeNo)) {
                throw new BillValidationException(line, "渠道交易号重复，首次出现于第 " + rows.get(tradeNo).line() + " 行");
            }
            long amount = amount(columns[2], line);
            if (!"SUCCESS".equals(columns[3])) {
                throw new BillValidationException(line, "账单状态必须为 SUCCESS");
            }
            Instant paidAt = paidAt(columns[4], line);
            if (paidAt.isBefore(from) || !paidAt.isBefore(to)) {
                throw new BillValidationException(line, "交易时间超出该账单日");
            }
            rows.put(tradeNo, new BillEntry(line, tradeNo, amount, "SUCCESS", paidAt));
        }
        return rows;
    }

    /** 读取固定列数，可加双引号，但字段语法本身不允许逗号、双引号或换行。 */
    private static String[] fields(String row, int line) {
        String[] columns = row.split(",", -1);
        if (columns.length != HEADER.size()) {
            throw new BillValidationException(line, "应为五列且字段内不允许逗号或换行");
        }
        for (int i = 0; i < columns.length; i++) {
            String value = columns[i];
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.indexOf('"') >= 0) {
                throw new BillValidationException(line, "CSV 字段引号不合法");
            }
            columns[i] = value;
        }
        return columns;
    }

    /** 金额使用正整数分，同时检查溢出。 */
    private static long amount(String value, int line) {
        try {
            if (CENTS.matcher(value).matches()) {
                long parsed = Long.parseLong(value);
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (NumberFormatException invalid) {
            // 超出 long 范围时沿用统一金额错误。
        }
        throw new BillValidationException(line, "金额必须是可用长整型表示的正整数分");
    }

    /** 读取带 UTC 偏移的 ISO-8601 成功时间。 */
    private static Instant paidAt(String value, int line) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new BillValidationException(line, "交易时间必须是带时区偏移的 ISO-8601 时间");
        }
    }
}

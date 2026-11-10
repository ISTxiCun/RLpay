package com.zrl.rlpay.reconciliation;

import com.zrl.rlpay.reconciliation.service.BillReconciliationService;
import com.zrl.rlpay.reconciliation.service.BillReconciliationService.Reason;
import com.zrl.rlpay.reconciliation.service.BillValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用 MySQL 验证账单导入、支付查询与差异分类。 */
@SpringBootTest(properties = {"rlpay.payment.jobs-enabled=false", "rlpay.reconciliation.import-enabled=true"})
@AutoConfigureMockMvc
class BillReconciliationIntegrationTest {
    private static final String HEADER = "channel_code,channel_trade_no,amount_cent,status,paid_at\n";
    private static final String INSERT = """
            INSERT INTO payment_order
                (payment_order_no, merchant_code, merchant_order_no, amount_cent, status,
                 channel_code, channel_trade_no, paid_at_utc)
            VALUES (?, 'RECON-TEST', ?, ?, ?, ?, ?, ?)
            """;
    private final String channel = "IT-" + UUID.randomUUID().toString().substring(0, 8);
    private final String other = channel + "-B";
    @Autowired private BillReconciliationService reconciliation;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    /** 核对全部差异、时区两端和渠道隔离，且不改变任何支付状态。 */
    @Test
    void reportsBothDirectionsAndAllMismatchReasons() {
        add(channel, "matched", 100, "SUCCESS", "2040-01-01T16:00:00");
        add(channel, "amount", 110, "SUCCESS", "2040-01-01T20:00:00");
        add(channel, "status", 100, "PENDING", null);
        add(channel, "dual", 110, "PENDING", null);
        add(channel, "reverse", 100, "SUCCESS", "2040-01-02T15:59:59.999");
        add(channel, "before", 100, "SUCCESS", "2040-01-01T15:59:59.999");
        add(channel, "at-end", 100, "SUCCESS", "2040-01-02T16:00:00");
        add(channel, "waiting", 100, "PENDING", null);
        add(other, "channel-only", 100, "SUCCESS", "2040-01-02T00:00:00");
        add(other, "other-reverse", 100, "SUCCESS", "2040-01-02T00:00:00");

        String rows = List.of("matched", "amount", "status", "dual", "channel-only").stream()
                .map(trade -> channel + "," + trade + ",100,SUCCESS,2040-01-02T00:00:00+08:00\n")
                .collect(Collectors.joining());
        var report = reconciliation.reconcile(channel, LocalDate.parse("2040-01-02"), "Asia/Shanghai",
                csv(HEADER + rows));
        assertThat(report.fromInclusive()).isEqualTo(Instant.parse("2040-01-01T16:00:00Z"));
        assertThat(report.toExclusive()).isEqualTo(Instant.parse("2040-01-02T16:00:00Z"));
        assertThat(report.billRowCount()).isEqualTo(5);
        assertThat(report.entries()).extracting(BillReconciliationService.Entry::channelTradeNo)
                .containsExactly("matched", "amount", "status", "dual", "channel-only", "reverse");
        assertThat(report.counts()).containsEntry(Reason.MATCHED, 1L).containsEntry(Reason.AMOUNT_MISMATCH, 2L)
                .containsEntry(Reason.STATUS_MISMATCH, 2L).containsEntry(Reason.CHANNEL_ONLY, 1L)
                .containsEntry(Reason.PLATFORM_ONLY, 1L);
        assertThat(report.entries().get(3).reasons()).containsExactly(Reason.AMOUNT_MISMATCH, Reason.STATUS_MISMATCH);
        assertThat(report.entries().get(4).platform()).isNull();
        assertThat(report.entries().get(5).bill()).isNull();
        assertThat(report.entries().get(0).platform().paidAt()).isEqualTo(report.fromInclusive());
        assertThat(jdbc.queryForMap("""
                SELECT channel_code, bill_zone, bill_row_count FROM reconciliation_batch WHERE batch_id = ?
                """, report.batchId())).containsEntry("channel_code", channel)
                .containsEntry("bill_zone", "Asia/Shanghai").containsEntry("bill_row_count", 5);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE channel_code = ? AND channel_trade_no = 'dual'",
                String.class, channel)).isEqualTo("PENDING");
    }

    /** 跨越两个查询批次，并验证相同成功时间的订单不漏页。 */
    @Test
    void pagesThroughSameMillisecondAndUsesDstWindow() {
        List<Object[]> data = new ArrayList<>();
        StringBuilder bill = new StringBuilder(HEADER);
        for (int i = 0; i < 1002; i++) {
            String tradeNo = "row-" + i;
            data.add(order(channel, tradeNo, 100, "SUCCESS", "2040-03-11T05:00:00"));
            if (i < 501) {
                bill.append(channel).append(',').append(tradeNo)
                        .append(",100,SUCCESS,2040-03-11T05:00:00Z\n");
            }
        }
        jdbc.batchUpdate(INSERT, data);
        var report = reconciliation.reconcile(channel, LocalDate.parse("2040-03-11"), "America/New_York",
                csv(bill.toString()));
        assertThat(report.fromInclusive()).isEqualTo(Instant.parse("2040-03-11T05:00:00Z"));
        assertThat(report.toExclusive()).isEqualTo(Instant.parse("2040-03-12T04:00:00Z"));
        assertThat(report.counts()).containsEntry(Reason.MATCHED, 501L).containsEntry(Reason.PLATFORM_ONLY, 501L);
        assertThat(report.entries()).extracting(BillReconciliationService.Entry::channelTradeNo)
                .containsExactlyElementsOf(IntStream.range(0, 1002).mapToObj(i -> "row-" + i).toList());
    }

    /** 错误定位到 CSV 物理行，失败批次不落库。 */
    @Test
    void invalidUploadReportsLineAndLeavesNoBatch() throws Exception {
        String valid = channel + ",repeat,100,SUCCESS,2040-01-01T16:00:00Z\n";
        upload(HEADER + valid + valid).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.line").value(3))
                .andExpect(jsonPath("$.reason").value("渠道交易号重复，首次出现于第 2 行"));
        upload(HEADER + valid.replace(",100,", ",9223372036854775808,")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.line").value(2));
        upload(HEADER + valid.replace("2040-01-01T16", "2040-01-02T16")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.line").value(2));
        upload(HEADER + valid.replace(channel, other)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.line").value(2));
        upload(HEADER + valid.replace("SUCCESS", "PENDING")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.line").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_batch WHERE channel_code = ?",
                Integer.class, channel)).isZero();

        upload("\uFEFF" + HEADER + valid.replace("repeat", "\"repeat\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.billRowCount").value(1))
                .andExpect(jsonPath("$.counts.CHANNEL_ONLY").value(1));
    }

    /** 跳过的民用日期无有效窗口；正常日期的空账单仍可识别平台缺单。 */
    @Test
    void emptyBillStillReconcilesButSkippedDateIsRejected() {
        assertThatThrownBy(() -> reconciliation.reconcile(channel, LocalDate.parse("2011-12-30"),
                "Pacific/Apia", csv(HEADER))).isInstanceOf(BillValidationException.class)
                .hasMessageContaining("不存在该账单日");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_batch WHERE channel_code = ?",
                Integer.class, channel)).isZero();
        add(channel, "reverse", 100, "SUCCESS", "2040-01-02T00:00:00");
        var report = reconciliation.reconcile(channel, LocalDate.parse("2040-01-02"), "Asia/Shanghai", csv(HEADER));
        assertThat(report.billRowCount()).isZero();
        assertThat(report.counts()).containsEntry(Reason.PLATFORM_ONLY, 1L);
    }

    /** 容器即使接受更大 multipart，应用也在读取内容之前拒绝。 */
    @Test
    void oversizedUploadDoesNotReadFileBytes() throws Exception {
        var file = new MockMultipartFile("file", "mock.csv", "text/csv", csv(HEADER)) {
            /** 模拟容器接收了超出应用限制的上传。 */
            @Override
            public long getSize() {
                return BillReconciliationService.MAX_FILE_BYTES + 1L;
            }

            /** 超大文件不应被读取。 */
            @Override
            public byte[] getBytes() {
                throw new AssertionError("超大文件被读取");
            }
        };
        mvc.perform(multipart("/internal/reconciliation/bills").file(file)
                        .param("channelCode", channel).param("billDate", "2040-01-02").param("billZone", "Asia/Shanghai"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("CSV 必须非空且不超过 1 MiB"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_batch WHERE channel_code = ?",
                Integer.class, channel)).isZero();
    }

    /** 通过实际 multipart 入口上传指定日期的模拟账单。 */
    private org.springframework.test.web.servlet.ResultActions upload(String content) throws Exception {
        return mvc.perform(multipart("/internal/reconciliation/bills")
                .file(new MockMultipartFile("file", "mock.csv", "text/csv", csv(content)))
                .param("channelCode", channel).param("billDate", "2040-01-02").param("billZone", "Asia/Shanghai"));
    }

    /** 写入指定渠道及时间的支付测试数据。 */
    private void add(String channelCode, String tradeNo, long amount, String status, String paidAt) {
        jdbc.update(INSERT, order(channelCode, tradeNo, amount, status, paidAt));
    }

    /** 为同渠道和不同渠道的测试交易提供彼此独立的平台编号。 */
    private static Object[] order(String channel, String trade, long amount, String status, String paidAt) {
        return new Object[]{channel + ":" + trade, channel + ":" + trade, amount, status, channel, trade,
                paidAt == null ? null : LocalDateTime.parse(paidAt)};
    }

    /** 编码模拟账单。 */
    private static byte[] csv(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 仅清理本用例使用的渠道和导入批次。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM reconciliation_batch WHERE channel_code = ?", channel);
        jdbc.update("DELETE FROM payment_order WHERE channel_code IN (?, ?)", channel, other);
    }
}

package com.zrl.rlpay.reconciliation.controller;

import com.zrl.rlpay.reconciliation.service.BillReconciliationService;
import com.zrl.rlpay.reconciliation.service.BillValidationException;
import com.zrl.rlpay.reconciliation.service.ReconciliationDifferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

/** 默认关闭的模拟账单导入入口，供受控联调环境使用。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rlpay.reconciliation.import-enabled", havingValue = "true")
public class BillReconciliationController {
    private final BillReconciliationService reconciliation;
    private final ReconciliationDifferenceService differences;

    /** 导入 CSV 并返回本批次逐笔报告。 */
    @PostMapping(path = "/internal/reconciliation/bills", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BillReconciliationService.Report upload(@RequestParam String channelCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
            @RequestParam String billZone, @RequestParam MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > BillReconciliationService.MAX_FILE_BYTES) {
            throw new BillValidationException(null, "CSV 必须非空且不超过 1 MiB");
        }
        return reconciliation.reconcile(channelCode, billDate, billZone, file.getBytes());
    }

    /** 分页查看差异。 */
    @GetMapping("/internal/reconciliation/differences")
    public List<ReconciliationDifferenceService.Difference> list(
            @RequestParam(defaultValue = "0") long afterId, @RequestParam(required = false) String status) {
        return differences.list(afterId, status);
    }

    /** 查询差异详情。 */
    @GetMapping("/internal/reconciliation/differences/{id}")
    public ResponseEntity<ReconciliationDifferenceService.Difference> find(@PathVariable long id) {
        return ResponseEntity.of(differences.find(id));
    }

    /** 核实并尝试处理差异。 */
    @PostMapping("/internal/reconciliation/differences/{id}/process")
    public ResponseEntity<ReconciliationDifferenceService.Difference> process(@PathVariable long id) {
        return ResponseEntity.of(differences.process(id));
    }

    /** 账单内容错误以 400 返回，包含行号及原因。 */
    @ExceptionHandler(BillValidationException.class)
    public ResponseEntity<ImportError> handleInvalidBill(BillValidationException invalid) {
        return ResponseEntity.badRequest().body(new ImportError(invalid.getLine(), invalid.getMessage()));
    }

    /** 可定位文件格式错误的响应。 */
    public record ImportError(Integer line, String reason) {
    }
}

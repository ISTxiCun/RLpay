package com.zrl.rlpay.payment.stripe;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

/** 仅在启用 Stripe 测试模式及内部对账入口时提供账单下载。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnExpression("${rlpay.stripe-sandbox.enabled:false} && ${rlpay.reconciliation.import-enabled:false}")
public class StripeBillController {
    private final StripeBillExporter bills;

    /** 按账单日和时区生成可直接导入的 Stripe 测试支付账单。 */
    @GetMapping(value = "/internal/reconciliation/stripe-test/bill", produces = "text/csv;charset=UTF-8")
    public String download(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate billDate,
                           @RequestParam String billZone) {
        try {
            ZoneId zone = ZoneId.of(billZone);
            var from = billDate.atStartOfDay(zone).toInstant();
            var to = billDate.plusDays(1).atStartOfDay(zone).toInstant();
            if (!from.isBefore(to) || billDate.getYear() < 2000 || billDate.getYear() > 9998) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe 账单日期无效");
            }
            return bills.downloadBill(from, to);
        } catch (DateTimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe 账单日期或时区无效");
        }
    }
}

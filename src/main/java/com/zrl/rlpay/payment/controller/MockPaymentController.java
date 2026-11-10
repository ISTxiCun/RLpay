package com.zrl.rlpay.payment.controller;

import com.zrl.rlpay.payment.channel.MockPaymentChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** 测试用模拟付款页，只更新模拟渠道自己的交易。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(MockPaymentChannel.PAYMENT_PATH)
public class MockPaymentController {
    private final MockPaymentChannel channel;

    /** 查看金额和渠道状态，待支付时提供两种模拟付款操作。 */
    @GetMapping(value = "/{tradeNo}", produces = MediaType.TEXT_HTML_VALUE)
    public String show(@PathVariable String tradeNo) {
        var trade = channel.query(tradeNo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String actions = "PENDING".equals(trade.status()) ? """
                <form method="post">
                  <button name="sendCallback" value="true">模拟付款并发送回调事件</button>
                  <button name="sendCallback" value="false">模拟付款，跳过回调事件</button>
                </form>
                """ : "";
        return """
                <!doctype html>
                <html lang="zh-CN"><meta charset="UTF-8"><title>RLpay 模拟付款</title>
                <h1>RLpay 模拟付款</h1><p>金额：%d 分</p><p>渠道状态：%s</p>%s
                </html>
                """.formatted(trade.amountCent(), trade.status(), actions);
    }

    /** 确认模拟付款，再重定向到结果页。 */
    @PostMapping("/{tradeNo}")
    public ResponseEntity<Void> complete(@PathVariable String tradeNo,
                                         @RequestParam(defaultValue = "false") boolean sendCallback) {
        var trade = channel.complete(tradeNo, sendCallback)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(MockPaymentChannel.paymentUrl(trade.channelTradeNo()))).build();
    }
}

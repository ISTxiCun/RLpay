package com.zrl.rlpay.payment.service;

import java.util.Optional;

/** 平台支付单的渠道发起与关联查询契约。 */
public interface PaymentChannelService {
    /** 为已提交的支付单获取或复用渠道交易。 */
    LaunchResult launch(PaymentOrderService.OrderView order);

    /** 按渠道和渠道交易号定位本地支付单，供后续回调及对账调用。 */
    Optional<PaymentOrderService.OrderView> findByChannelTradeNo(String channelCode, String channelTradeNo);

    /** 商城所需的当前支付状态和付款链接。 */
    record LaunchResult(String status, String paymentUrl) {
    }
}

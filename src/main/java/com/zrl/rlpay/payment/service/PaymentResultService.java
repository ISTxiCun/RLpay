package com.zrl.rlpay.payment.service;

import com.zrl.rlpay.payment.channel.MockPaymentChannel;

/** 处理可信模拟渠道回调，并提供平台内部主动查单。 */
public interface PaymentResultService {
    /** 接收进程内的模拟渠道结果。 */
    Outcome acceptCallback(MockPaymentChannel.Callback callback);

    /** 使用平台已关联的渠道交易号主动查询并处理结果。 */
    Outcome query(String paymentOrderNo);

    enum Outcome {
        SUCCESS, PENDING, RETRY, REJECTED
    }
}

package com.zrl.rlpay.payment.service;

import com.zrl.rlpay.payment.channel.ChannelResultProvider;

/** 处理已规范化的渠道结果，并提供平台内部主动查单。 */
public interface PaymentResultService {
    /** 接收渠道适配器已经验证并规范化的结果。 */
    Outcome acceptResult(ChannelResultProvider.Result result);

    /** 使用平台已关联的渠道交易号主动查询并处理结果。 */
    Outcome query(String paymentOrderNo);

    enum Outcome {
        SUCCESS, EXPIRED, PENDING, RETRY, REJECTED
    }
}

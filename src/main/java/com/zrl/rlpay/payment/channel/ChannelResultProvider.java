package com.zrl.rlpay.payment.channel;

import java.time.Instant;
import java.util.Optional;

/** 支付核心主动读取渠道结果时使用的接口。 */
public interface ChannelResultProvider {
    /** 渠道持久化编码。 */
    String channelCode();

    /** 按渠道交易号查询规范化结果；暂时无法确认时返回空，交由平台重试。 */
    Optional<Result> queryResult(String tradeNo);

    /** 进入平台状态机前统一的渠道结果。 */
    record Result(String channelCode, String channelTradeNo, long amountCent, String status, Instant paidAt) {
    }
}

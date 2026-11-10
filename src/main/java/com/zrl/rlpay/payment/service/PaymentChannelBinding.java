package com.zrl.rlpay.payment.service;

/** 平台支付单绑定渠道交易的唯一写入入口。 */
public interface PaymentChannelBinding {
    /** 绑定已受理交易，返回同一事务内的最新平台状态。 */
    String bind(String merchantCode, String paymentNo, String channelCode, String tradeNo);

    /** 支付单或渠道交易已经绑定到其他对象。 */
    class Conflict extends IllegalStateException {
        /** 构造统一的绑定冲突。 */
        public Conflict() {
            super("平台支付单与渠道交易关联冲突");
        }
    }
}

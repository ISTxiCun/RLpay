package com.zrl.rlpay.payment.stripe;

/** Stripe 测试模式的发起与已验签回调处理。 */
public interface StripeCheckoutService {
    /** 为认证商户的已保存支付单创建或复用 Checkout。 */
    Launch start(String merchantCode, String paymentNo);

    /** 处理已认证的 Checkout 完成事件，尚未确认时应由 Stripe 重投。 */
    boolean confirm(String channelTradeNo);

    /** 支付发起结果。 */
    record Launch(String paymentOrderNo, String status, String paymentUrl) {
    }
}

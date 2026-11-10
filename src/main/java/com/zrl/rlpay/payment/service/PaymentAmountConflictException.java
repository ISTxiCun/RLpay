package com.zrl.rlpay.payment.service;

/** 已有支付单的金额与同一商户订单的重试金额不一致。 */
public class PaymentAmountConflictException extends RuntimeException {
    /** 返回供调用方识别的业务错误。 */
    public PaymentAmountConflictException() {
        super("同一商户订单的支付金额与原单不一致");
    }
}

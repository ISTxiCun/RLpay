package com.zrl.rlpay.mall.vo;

import com.zrl.rlpay.mall.service.MallPaymentGateway;

/** 商城发起支付时返回给 HTTP 调用方的请求参数与支付应答。 */
public record MallPaymentVO(MallPaymentGateway.Request paymentRequest, MallPaymentGateway.Response paymentResponse) {
}

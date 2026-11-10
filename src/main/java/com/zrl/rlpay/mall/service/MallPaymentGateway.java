package com.zrl.rlpay.mall.service;

/** 商城侧的支付调用契约，供后续接入支付模块。 */
public interface MallPaymentGateway {
    String MERCHANT_CODE = "MALL_MOCK";

    /** 根据商户订单号创建或获取支付单。 */
    Response createOrGet(Request request);

    /** 商城传给支付模块的商户号、订单号和金额（分）。 */
    record Request(String merchantCode, String merchantOrderNo, long amountCent) {
    }

    /** 商城接收的平台支付单号、状态和可用的付款链接。 */
    record Response(String paymentOrderNo, String status, String paymentUrl) {
    }
}

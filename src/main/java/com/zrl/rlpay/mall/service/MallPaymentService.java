package com.zrl.rlpay.mall.service;

import com.zrl.rlpay.mall.vo.MallPaymentVO;

/** 商城发起支付的服务接口。 */
public interface MallPaymentService {
    /** 为指定商户订单发起支付，并返回支付请求和应答。 */
    MallPaymentVO requestPayment(String merchantOrderNo);
}

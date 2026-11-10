package com.zrl.rlpay.payment.service;

import java.util.Optional;

/** 支付单内部契约。商户范围由受信的进程内调用方提供。 */
public interface PaymentOrderService {
    /** 按可信商户及订单号创建支付单，或复用同金额原单。 */
    OrderView createOrGet(String trustedMerchantCode, String merchantOrderNo, long amountCent);

    /** 查询商户所属的平台支付单；不存在时返回空。 */
    Optional<OrderView> findByPaymentOrderNo(String trustedMerchantCode, String paymentOrderNo);

    /** 查询商户订单对应的支付单；不存在时返回空。 */
    Optional<OrderView> findByMerchantOrderNo(String trustedMerchantCode, String merchantOrderNo);

    /** 支付单的内部查询和创建结果。 */
    record OrderView(String paymentOrderNo, String merchantCode, String merchantOrderNo,
                     long amountCent, String status) {
    }
}

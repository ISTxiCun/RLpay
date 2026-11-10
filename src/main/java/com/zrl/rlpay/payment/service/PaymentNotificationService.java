package com.zrl.rlpay.payment.service;

/** 平台支付结果的商城通知投递。 */
public interface PaymentNotificationService {
    /** 投递一批到期通知。 */
    void deliverDue();

    /** 将失败终止的记录重新放入投递队列，供内部人工操作。 */
    boolean retryFailed(long notificationId);
}

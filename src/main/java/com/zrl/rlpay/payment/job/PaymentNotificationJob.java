package com.zrl.rlpay.payment.job;

import com.zrl.rlpay.payment.service.PaymentNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时投递已持久化的商城通知。 */
@Component
@RequiredArgsConstructor
public class PaymentNotificationJob {
    private final PaymentNotificationService notifications;

    /** 扫描待投递以及租约到期的通知。 */
    @Scheduled(fixedDelayString = "${rlpay.payment.notification-job-delay-ms:5000}")
    public void deliverDue() {
        notifications.deliverDue();
    }
}

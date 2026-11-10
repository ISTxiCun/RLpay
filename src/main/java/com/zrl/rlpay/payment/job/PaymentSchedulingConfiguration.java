package com.zrl.rlpay.payment.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 默认启动支付后台任务，测试可以关掉调度后逐轮触发。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "rlpay.payment.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentSchedulingConfiguration {
    /** 渠道补查仍串行，避免无界并发外部查询。 */
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler paymentResultScheduler() {
        return scheduler("payment-result-");
    }

    /** 商城通知不与渠道网络请求共用调度线程。 */
    @Bean
    ThreadPoolTaskScheduler paymentNotificationScheduler() {
        return scheduler("payment-notification-");
    }

    /** 创建一个只串行执行本类任务的调度器。 */
    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}

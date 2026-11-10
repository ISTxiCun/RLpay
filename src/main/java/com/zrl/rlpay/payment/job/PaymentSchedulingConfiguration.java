package com.zrl.rlpay.payment.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 默认启动支付后台任务，测试可以关掉调度后逐轮触发。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "rlpay.payment.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentSchedulingConfiguration {
}

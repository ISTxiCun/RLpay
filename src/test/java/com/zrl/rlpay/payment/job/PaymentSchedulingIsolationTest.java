package com.zrl.rlpay.payment.job;

import com.zrl.rlpay.payment.channel.ChannelLaunchRecovery;
import com.zrl.rlpay.payment.service.PaymentNotificationService;
import com.zrl.rlpay.payment.service.PaymentResultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 模拟渠道 I/O 停滞，验证真实 Spring 调度器仍能执行商城通知。 */
@SpringJUnitConfig(PaymentSchedulingIsolationTest.Fixtures.class)
@TestPropertySource(properties = {
        "rlpay.payment.result-job-delay-ms=60000", "rlpay.payment.notification-job-delay-ms=20"
})
class PaymentSchedulingIsolationTest {
    @Autowired private Signals signals;

    /** 查单线程被阻塞期间，商城通知线程应执行并记录等待时间。 */
    @Test
    void notificationsContinueWhileChannelIsBlocked() throws Exception {
        try {
            assertThat(signals.channelEntered.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            assertThat(signals.notificationWhileBlocked.await(3, TimeUnit.SECONDS)).isTrue();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            System.out.println("Notification observed during blocked channel within " + elapsed + " ms");
            assertThat(signals.releaseChannel.getCount()).isEqualTo(1);
        } finally {
            signals.releaseChannel.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PaymentSchedulingConfiguration.class, PaymentResultJobs.class, PaymentNotificationJob.class})
    static class Fixtures {
        /** 两个工作线程之间的可观测信号。 */
        @Bean
        Signals signals() {
            return new Signals();
        }

        /** 阻塞渠道恢复线程，直到测试释放。 */
        @Bean
        ChannelLaunchRecovery slowChannel(Signals signals) {
            return () -> {
                signals.channelEntered.countDown();
                try {
                    signals.releaseChannel.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
        }

        /** 用最小替身检测通知真实调度，而非测试线程直接调用。 */
        @Bean
        PaymentNotificationService notifications(Signals signals) {
            return new PaymentNotificationService() {
                @Override
                public void deliverDue() {
                    if (signals.channelEntered.getCount() == 0 && signals.releaseChannel.getCount() == 1) {
                        signals.notificationWhileBlocked.countDown();
                    }
                }

                @Override
                public boolean retryFailed(long id) {
                    return false;
                }
            };
        }

        /** 调度测试不访问数据库。 */
        @Bean
        JdbcTemplate jdbc() {
            return mock(JdbcTemplate.class);
        }

        /** 只验证调度隔离，不处理真实订单。 */
        @Bean
        PaymentResultService results() {
            return mock(PaymentResultService.class);
        }
    }

    private static final class Signals {
        private final CountDownLatch channelEntered = new CountDownLatch(1);
        private final CountDownLatch releaseChannel = new CountDownLatch(1);
        private final CountDownLatch notificationWhileBlocked = new CountDownLatch(1);
    }
}

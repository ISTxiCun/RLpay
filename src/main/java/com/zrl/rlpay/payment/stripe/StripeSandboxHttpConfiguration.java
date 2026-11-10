package com.zrl.rlpay.payment.stripe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Checkout 和账单共享同一个只允许 Stripe 测试密钥的 HTTP 客户端。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
class StripeSandboxHttpConfiguration {
    /** 初始化官方 HTTPS 端点以及连接、读取超时。 */
    @Bean
    RestClient stripeSandboxRestClient(@Value("${RLPAY_STRIPE_TEST_SECRET_KEY:}") String key) {
        if (!(key.startsWith("sk_test_") || key.startsWith("rk_test_")) || key.length() <= 8) {
            throw new IllegalArgumentException("只允许 Stripe 测试 API 密钥");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("https://api.stripe.com").requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(key)).build();
    }
}

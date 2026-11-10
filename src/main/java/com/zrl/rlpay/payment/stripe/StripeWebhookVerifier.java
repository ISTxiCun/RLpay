package com.zrl.rlpay.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;

import static com.zrl.rlpay.payment.stripe.StripeSandboxProtocol.isSession;

/** 只负责 Stripe 测试回调验签与事件解析，不调用网络。 */
@Component
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeWebhookVerifier {
    private final ObjectMapper json;
    private final byte[] webhookSecret;
    private final Clock clock;

    /** 读取 Stripe 回调密钥，使用系统 UTC 时间验签。 */
    @Autowired
    public StripeWebhookVerifier(ObjectMapper json, @Value("${RLPAY_STRIPE_WEBHOOK_SECRET:}") String webhookSecret) {
        this(json, webhookSecret, Clock.systemUTC());
    }

    /** 允许签名测试使用固定时钟。 */
    StripeWebhookVerifier(ObjectMapper json, String webhookSecret, Clock clock) {
        if (!webhookSecret.startsWith("whsec_") || webhookSecret.length() <= 6) {
            throw new IllegalArgumentException("需要配置 Stripe Webhook 签名密钥");
        }
        this.json = json;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** 先验签再解析，只处理测试模式的付款完成或会话过期事件。 */
    public Optional<String> authenticateCheckoutEvent(byte[] body, String header) {
        if (!validSignature(body, header)) {
            throw new IllegalArgumentException("Stripe Webhook 签名无效");
        }
        try {
            JsonNode event = json.readTree(body);
            if (event == null || !event.path("livemode").isBoolean() || event.path("livemode").asBoolean()) {
                throw new IllegalArgumentException("仅接受 Stripe 测试事件");
            }
            String type = event.path("type").asText();
            if (!"checkout.session.completed".equals(type) && !"checkout.session.expired".equals(type)) {
                return Optional.empty();
            }
            String tradeNo = event.path("data").path("object").path("id").asText();
            if (!isSession(tradeNo)) {
                throw new IllegalArgumentException("Stripe 会话标识无效");
            }
            return Optional.of(tradeNo);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Stripe Webhook 格式无效");
        }
    }

    /** 对时间戳、分隔点与原始请求字节按 Stripe 规范验签。 */
    private boolean validSignature(byte[] body, String header) {
        if (header == null || header.length() > 1024) {
            return false;
        }
        String time = null;
        var signatures = new java.util.ArrayList<String>();
        for (String item : header.split(",")) {
            if (item.startsWith("t=")) {
                if (time != null) {
                    return false;
                }
                time = item.substring(2);
            } else if (item.startsWith("v1=") && item.substring(3).matches("[a-fA-F0-9]{64}")) {
                signatures.add(item.substring(3));
            }
        }
        if (time == null || !time.matches("[0-9]{1,12}")) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        long signedAt = Long.parseLong(time);
        if (signedAt < now - 300 || signedAt > now + 300) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            mac.update((time + ".").getBytes(StandardCharsets.US_ASCII));
            byte[] expected = mac.doFinal(body);
            return signatures.stream().anyMatch(value -> MessageDigest.isEqual(expected, HexFormat.of().parseHex(value)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Stripe 验签算法不可用", unavailable);
        }
    }
}

package com.zrl.rlpay.payment.merchant;

import com.zrl.rlpay.mall.service.MallPaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 验证请求签名并将凭证映射为可信商户。 */
@Component
@ConditionalOnProperty(name = "rlpay.merchant-api.enabled", havingValue = "true")
@EnableConfigurationProperties(MerchantApiProperties.class)
public class MerchantAuthentication {
    private static final long WINDOW_SECONDS = 300;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,12}");
    private static final Pattern SIGNATURE = Pattern.compile("[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final Map<String, MerchantKey> credentials;

    /** 启动时检查启用凭证，拒绝重复 ID 和模拟商城的保留商户号。 */
    public MerchantAuthentication(JdbcTemplate jdbc, MerchantApiProperties properties) {
        this.jdbc = jdbc;
        var configured = new HashMap<String, MerchantKey>();
        for (var credential : properties.credentials() == null ? List.<MerchantApiProperties.Credential>of()
                : properties.credentials()) {
            if (!credential.enabled()) {
                continue;
            }
            if (!valid(IDENTIFIER, credential.id()) || !valid(IDENTIFIER, credential.merchantCode())
                    || MallPaymentGateway.MERCHANT_CODE.equals(credential.merchantCode())
                    || configured.containsKey(credential.id())) {
                throw new IllegalStateException("商户凭证配置无效");
            }
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(credential.secret() == null ? "" : credential.secret());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("商户凭证密钥必须是 Base64");
            }
            if (secret.length < 32) {
                throw new IllegalStateException("商户凭证密钥至少需要 32 个字节");
            }
            configured.put(credential.id(), new MerchantKey(credential.merchantCode(), secret));
        }
        this.credentials = Map.copyOf(configured);
    }

    /** 核对原始请求字节后原子领取 nonce，返回认证商户。 */
    public String verify(String method, String path, String keyId, String timestamp, String nonce,
                         String signature, byte[] body) {
        if (!valid(IDENTIFIER, keyId) || !valid(TIMESTAMP, timestamp) || !valid(NONCE, nonce)
                || !valid(SIGNATURE, signature)) {
            throw denied();
        }
        long signedAt = Long.parseLong(timestamp);
        var credential = credentials.get(keyId);
        if (credential == null) {
            throw denied();
        }
        byte[] expected = signature(method, path, keyId, timestamp, nonce, body, credential.secret());
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
            throw denied();
        }
        try {
            if (jdbc.update("""
                    INSERT INTO merchant_request_nonce (credential_id, nonce, expires_at_epoch)
                    SELECT ?, ?, ? WHERE ? BETWEEN UNIX_TIMESTAMP() - ? AND UNIX_TIMESTAMP() + ?
                    """, keyId, nonce, signedAt + WINDOW_SECONDS, signedAt, WINDOW_SECONDS, WINDOW_SECONDS) != 1) {
                throw denied();
            }
        } catch (DuplicateKeyException duplicate) {
            throw denied();
        }
        return credential.merchantCode();
    }

    /** 定期清理超过验签时间窗的防重放记录。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanExpired() {
        jdbc.update("DELETE FROM merchant_request_nonce WHERE expires_at_epoch < UNIX_TIMESTAMP() LIMIT 1000");
    }

    /** 对方法、原始路径、凭证、时间、nonce 和请求体摘要进行签名。 */
    private static byte[] signature(String method, String path, String keyId, String time, String nonce,
                                    byte[] body, byte[] secret) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", method, path, keyId, time, nonce, bodyHash);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("验签算法不可用", unavailable);
        }
    }

    /** 统一鉴权拒绝响应。 */
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "商户认证失败");
    }

    /** 校验非空凭证字段。 */
    private static boolean valid(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private record MerchantKey(String merchantCode, byte[] secret) {
    }
}

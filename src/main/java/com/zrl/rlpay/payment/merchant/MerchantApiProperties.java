package com.zrl.rlpay.payment.merchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 外部商户接入配置，密钥从受控配置提供。 */
@ConfigurationProperties("rlpay.merchant-api")
public record MerchantApiProperties(List<Credential> credentials) {
    /** 每个凭证独立绑定一个可信商户。 */
    public record Credential(String id, String merchantCode, String secret, boolean enabled) {
        /** 防止配置对象被打印时带出签名密钥。 */
        @Override
        public String toString() {
            return "Credential[id=" + id + ", merchantCode=" + merchantCode + ", enabled=" + enabled + "]";
        }
    }
}

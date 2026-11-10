package com.zrl.rlpay.payment.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用真实 MySQL 验证凭证范围、请求完整性与持久化防重放。 */
@SpringBootTest(properties = {
        "rlpay.payment.jobs-enabled=false", "rlpay.merchant-api.enabled=true",
        "rlpay.merchant-api.credentials[0].id=key-a", "rlpay.merchant-api.credentials[0].merchant-code=IT-MERCHANT-A",
        "rlpay.merchant-api.credentials[0].secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "rlpay.merchant-api.credentials[0].enabled=true",
        "rlpay.merchant-api.credentials[1].id=key-b", "rlpay.merchant-api.credentials[1].merchant-code=IT-MERCHANT-B",
        "rlpay.merchant-api.credentials[1].secret=AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "rlpay.merchant-api.credentials[1].enabled=true"
})
@AutoConfigureMockMvc
class MerchantApiIntegrationTest {
    private static final String PATH = "/api/merchant/payments";
    private final String order = "IT-" + UUID.randomUUID();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    /** 同订单号在不同商户互相隔离，同商户重试复用支付单。 */
    @Test
    void merchantIdentityScopesCreateAndBothQueries() throws Exception {
        String body = "{\"merchantOrderNo\":\"" + order + "\",\"amountCent\":1290}";
        var first = mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
                        "POST", PATH, "key-a", body, now(), nonce()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merchantCode").value("IT-MERCHANT-A"))
                .andReturn().getResponse().getContentAsString();
        String payment = json.readTree(first).path("paymentOrderNo").asText();
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
                        "POST", PATH, "key-a", body, now(), nonce()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentOrderNo").value(payment));
        String other = body.replace("1290", "1300");
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(other),
                        "POST", PATH, "key-b", other, now(), nonce()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merchantCode").value("IT-MERCHANT-B"));
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(other),
                        "POST", PATH, "key-a", other, now(), nonce())).andExpect(status().isConflict());
        String byPayment = PATH + "/" + payment;
        mvc.perform(signed(get(byPayment), "GET", byPayment, "key-b", "", now(), nonce()))
                .andExpect(status().isNotFound());
        mvc.perform(signed(get(byPayment), "GET", byPayment, "key-a", "", now(), nonce()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountCent").value(1290));
        String byOrder = PATH + "/by-order/" + order;
        mvc.perform(signed(get(byOrder), "GET", byOrder, "key-b", "", now(), nonce()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountCent").value(1300));
    }

    /** 篡改请求不消耗 nonce，首次合法请求通过，重放和过期被拒。 */
    @Test
    void rejectsTamperingReplayAndExpiredRequests() throws Exception {
        String body = "{\"merchantOrderNo\":\"" + order + "\",\"amountCent\":1290}";
        String time = now();
        String nonce = nonce();
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body.replace("1290", "1291")),
                        "POST", PATH, "key-a", body, time, nonce)).andExpect(status().isUnauthorized());
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
                        "POST", PATH, "key-a", body, time, nonce)).andExpect(status().isOk());
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
                        "POST", PATH, "key-a", body, time, nonce)).andExpect(status().isUnauthorized());
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
                        "POST", PATH, "key-a", body, Long.toString(Instant.now().getEpochSecond() - 301), nonce()))
                .andExpect(status().isUnauthorized());
        String injected = body.substring(0, body.length() - 1) + ",\"merchantCode\":\"IT-MERCHANT-B\"}";
        mvc.perform(signed(post(PATH).contentType(MediaType.APPLICATION_JSON).content(injected),
                        "POST", PATH, "key-a", injected, now(), nonce())).andExpect(status().isBadRequest());
    }

    /** 模拟商户对客户端实际发送的原始字节签名。 */
    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request, String method,
            String path, String key, String body, String time, String nonce) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        byte[] secret = new byte[32];
        if ("key-b".equals(key)) {
            secret[0] = 1;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(
                String.join("\n", method, path, key, time, nonce, digest).getBytes(StandardCharsets.UTF_8)));
        return request.header("X-Key-Id", key).header("X-Timestamp", time)
                .header("X-Nonce", nonce).header("X-Signature", signature);
    }

    /** 生成请求时间。 */
    private static String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    /** 生成单次请求标识。 */
    private static String nonce() {
        return UUID.randomUUID().toString();
    }

    /** 清理用例写入的数据。 */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM payment_order WHERE merchant_order_no=? AND merchant_code IN (?,?)",
                order, "IT-MERCHANT-A", "IT-MERCHANT-B");
        jdbc.update("DELETE FROM merchant_request_nonce WHERE credential_id IN (?,?)", "key-a", "key-b");
    }
}

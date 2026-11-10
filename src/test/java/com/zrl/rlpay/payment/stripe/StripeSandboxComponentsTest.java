package com.zrl.rlpay.payment.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import java.net.SocketTimeoutException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** 用官方接口形状的 HTTP 替身验证 Checkout、查单和账单。 */
class StripeSandboxComponentsTest {
    private static final String SESSION = "cs_test_" + "a".repeat(58);
    private static final String SECRET = "whsec_local_fixture";
    private static final Instant PAID = Instant.parse("2026-11-11T00:30:00Z");
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.stripe.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient api = builder.build();
    private final StripeCheckoutClient checkout = new StripeCheckoutClient(api,
            "https://example.invalid/success", "https://example.invalid/cancel");
    private final StripeBillExporter bills = new StripeBillExporter(api);
    private final StripeWebhookVerifier webhook = new StripeWebhookVerifier(new ObjectMapper(), SECRET,
            Clock.fixed(PAID, ZoneOffset.UTC));

    /** 创建请求使用稳定幂等键，查询成功采用渠道交易时间。 */
    @Test
    void checkoutAndQueryUseStripeProtocol() {
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key", "rlpay-checkout-PAY-TEST"))
                .andRespond(withSuccess(session(false), MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andExpect(request -> assertThat(request.getURI().getQuery()).contains("expand[]=payment_intent.latest_charge"))
                .andRespond(withSuccess(session(true), MediaType.APPLICATION_JSON));
        assertThat(checkout.create("PAY-TEST", 1290, checkout.requestForm("PAY-TEST", 1290)).tradeNo()).isEqualTo(SESSION);
        var result = checkout.queryResult(SESSION).orElseThrow();
        assertThat(result.paidAt()).isEqualTo(PAID);
        assertThat(result.amountCent()).isEqualTo(1290);
        assertThat(result.status()).isEqualTo("SUCCESS");
        server.verify();
    }

    /** 超时、限流和渠道故障应保留待核实，凭据错误仍能被识别。 */
    @Test
    void temporaryLookupFailuresCanBeRetried() {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andRespond(withException(new SocketTimeoutException("fixture timeout")));
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(checkout.queryResult(SESSION)).isEmpty();
        assertThat(checkout.queryResult(SESSION)).isEmpty();
        assertThat(checkout.queryResult(SESSION)).isEmpty();
        assertThatThrownBy(() -> checkout.queryResult(SESSION)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        server.verify();
    }

    /** 渠道明确过期且未付款才是过期，普通未付款仍待支付。 */
    @Test
    void queryDistinguishesExpiredAndOpenSessions() {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andRespond(withSuccess(session(false), MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith(SESSION))
                .andRespond(withSuccess("{\"status\":\"expired\"," + session(false).substring(1),
                        MediaType.APPLICATION_JSON));
        assertThat(checkout.queryResult(SESSION).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(checkout.queryResult(SESSION).orElseThrow().status()).isEqualTo("EXPIRED");
        server.verify();
    }

    /** 账单使用 Charge 的发生时间和唯一 Checkout 会话号。 */
    @Test
    void billMapsChargeToCheckout() {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/charges"))
                .andExpect(request -> assertThat(request.getURI().getQuery())
                        .contains("created[gte]=" + PAID.minusSeconds(1).getEpochSecond()))
                .andRespond(withSuccess("""
                        {"has_more":false,"data":[{"id":"ch_test","status":"succeeded","paid":true,"captured":true,
                         "livemode":false,"currency":"usd","created":%d,"amount":1290,"payment_intent":"pi_test",
                         "metadata":{"rlpay_order":"PAY-TEST"}}]}
                        """.formatted(PAID.getEpochSecond()), MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v1/checkout/sessions"))
                .andExpect(queryParam("payment_intent", "pi_test"))
                .andRespond(withSuccess("{\"has_more\":false,\"data\":["
                        + session(true).replace(expandedIntent(), "\"pi_test\"") + "]}", MediaType.APPLICATION_JSON));
        assertThat(bills.downloadBill(PAID.minusSeconds(1), PAID.plusSeconds(1)))
                .contains("STRIPE_TEST," + SESSION + ",1290,SUCCESS,2026-11-11T00:30:00Z");
        server.verify();
    }

    /** 只有原始内容、测试事件及有效时间窗口同时匹配才接受回调。 */
    @Test
    void webhookRejectsTamperedStaleAndLiveEvents() throws Exception {
        String payload = """
                {"livemode":false,"type":"checkout.session.completed","data":{"object":{"id":"%s"}}}
                """.formatted(SESSION);
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        assertThat(webhook.authenticateCheckoutEvent(body, signed(PAID, body))).contains(SESSION);
        assertThatThrownBy(() -> webhook.authenticateCheckoutEvent(body, signed(PAID.minusSeconds(301), body)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] live = payload.replace("false", "true").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> webhook.authenticateCheckoutEvent(live, signed(PAID, body)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> webhook.authenticateCheckoutEvent(live, signed(PAID, live)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 模拟 Stripe 签名原始字节。 */
    private static String signed(Instant time, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((time.getEpochSecond() + ".").getBytes(StandardCharsets.US_ASCII));
        return "t=" + time.getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    /** 构造测试会话数据。 */
    private static String session(boolean paid) {
        return """
                {"id":"%s","livemode":false,"currency":"usd","amount_total":1290,"payment_status":"%s",
                 "url":"https://checkout.stripe.com/c/pay/demo","metadata":{"rlpay_order":"PAY-TEST"},
                 "payment_intent":%s}
                """.formatted(SESSION, paid ? "paid" : "unpaid", paid ? expandedIntent() : "null");
    }

    /** 模拟带最新 Charge 的 PaymentIntent。 */
    private static String expandedIntent() {
        return """
                {"status":"succeeded","latest_charge":{"paid":true,"created":%d,"amount":1290}}
                """.formatted(PAID.getEpochSecond());
    }
}

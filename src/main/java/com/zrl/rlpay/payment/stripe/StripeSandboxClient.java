package com.zrl.rlpay.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.payment.channel.ChannelResultProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** 调用 Stripe Checkout 测试 API，并验证 Webhook 原始字节。 */
@Component
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeSandboxClient implements ChannelResultProvider {
    public static final String CODE = "STRIPE_TEST";
    private static final String CURRENCY = "usd";
    private static final Pattern SESSION = Pattern.compile("cs_test_[A-Za-z0-9]{1,247}");
    private final RestClient api;
    private final ObjectMapper json;
    private final byte[] webhookSecret;
    private final URI successUrl;
    private final URI cancelUrl;
    private final Clock clock;

    /** 只连接 Stripe 官方 HTTPS API，并且拒绝生产密钥。 */
    @org.springframework.beans.factory.annotation.Autowired
    public StripeSandboxClient(ObjectMapper json,
            @Value("${RLPAY_STRIPE_TEST_SECRET_KEY:}") String key,
            @Value("${RLPAY_STRIPE_WEBHOOK_SECRET:}") String webhookSecret,
            @Value("${rlpay.stripe-sandbox.success-url:}") String successUrl,
            @Value("${rlpay.stripe-sandbox.cancel-url:}") String cancelUrl) {
        this(createApi(key), json, webhookSecret, URI.create(successUrl), URI.create(cancelUrl), Clock.systemUTC());
    }

    /** 允许用受控 HTTP 替身及固定时钟验证签名和协议。 */
    StripeSandboxClient(RestClient api, ObjectMapper json, String webhookSecret, URI successUrl, URI cancelUrl, Clock clock) {
        if (!webhookSecret.startsWith("whsec_") || webhookSecret.length() <= 6) {
            throw new IllegalArgumentException("需要配置 Stripe Webhook 签名密钥");
        }
        this.api = api;
        this.json = json;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.successUrl = https(successUrl);
        this.cancelUrl = https(cancelUrl);
        this.clock = clock;
    }

    /** 初始化带连接和读取超时的 Stripe API 客户端。 */
    private static RestClient createApi(String key) {
        if (!(key.startsWith("sk_test_") || key.startsWith("rk_test_")) || key.length() <= 8) {
            throw new IllegalArgumentException("只允许 Stripe 测试 API 密钥");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("https://api.stripe.com").requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(key)).build();
    }

    /** 创建仅支持 USD 卡支付的测试 Checkout Session。 */
    public Checkout create(String paymentNo, long amountCent) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "payment");
        form.add("success_url", successUrl.toString());
        form.add("cancel_url", cancelUrl.toString());
        form.add("payment_method_types[0]", "card");
        form.add("client_reference_id", paymentNo);
        form.add("metadata[rlpay_order]", paymentNo);
        form.add("payment_intent_data[metadata][rlpay_order]", paymentNo);
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", CURRENCY);
        form.add("line_items[0][price_data][unit_amount]", Long.toString(amountCent));
        form.add("line_items[0][price_data][product_data][name]", "RLpay sandbox order");
        JsonNode response = api.post().uri("/v1/checkout/sessions")
                .header("Idempotency-Key", "rlpay-checkout-" + paymentNo)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        String tradeNo = validateSession(response);
        if (!paymentNo.equals(response.path("metadata").path("rlpay_order").asText())
                || response.path("amount_total").longValue() != amountCent) {
            throw new IllegalStateException("Stripe 测试会话与平台订单不符");
        }
        URI url = https(URI.create(response.path("url").asText()));
        if (!"checkout.stripe.com".equalsIgnoreCase(url.getHost()) || url.toString().length() > 2048) {
            throw new IllegalStateException("Stripe 返回了未知付款地址");
        }
        return new Checkout(tradeNo, url.toString());
    }

    /** 返回用于路由的 Stripe 测试渠道编码。 */
    @Override
    public String channelCode() {
        return CODE;
    }

    /** 查询测试会话；成功时间取已支付卡交易的渠道时间。 */
    @Override
    public Optional<Result> queryResult(String tradeNo) {
        if (!isSession(tradeNo)) {
            return Optional.empty();
        }
        JsonNode session = api.get().uri(builder -> builder.path("/v1/checkout/sessions/{id}")
                        .queryParam("expand[]", "payment_intent.latest_charge").build(tradeNo))
                .retrieve().body(JsonNode.class);
        if (!tradeNo.equals(validateSession(session))) {
            throw new IllegalStateException("Stripe 查单返回了其他会话");
        }
        long amount = session.path("amount_total").longValue();
        if (!"paid".equals(session.path("payment_status").asText())) {
            return Optional.of(new Result(CODE, tradeNo, amount, "PENDING", null));
        }
        JsonNode intent = session.path("payment_intent");
        JsonNode charge = intent.path("latest_charge");
        if (!"succeeded".equals(intent.path("status").asText()) || !charge.path("paid").asBoolean(false)
                || charge.path("created").longValue() <= 0 || charge.path("amount").longValue() != amount) {
            throw new IllegalStateException("Stripe 尚未返回可核实的支付成功记录");
        }
        return Optional.of(new Result(CODE, tradeNo, amount, "SUCCESS",
                Instant.ofEpochSecond(charge.path("created").longValue())));
    }

    /** 从 Stripe 测试卡交易分页生成支付账单，使用 Checkout 会话号对账。 */
    public String downloadBill(Instant from, Instant to) {
        StringBuilder csv = new StringBuilder("channel_code,channel_trade_no,amount_cent,status,paid_at\n");
        String after = null;
        var trades = new HashSet<String>();
        for (int page = 0; page < 100; page++) {
            String cursor = after;
            JsonNode response = api.get().uri(builder -> {
                var uri = builder.path("/v1/charges").queryParam("limit", 100)
                        .queryParam("created[gte]", from.getEpochSecond())
                        .queryParam("created[lt]", to.getEpochSecond());
                if (cursor != null) {
                    uri.queryParam("starting_after", cursor);
                }
                return uri.build();
            }).retrieve().body(JsonNode.class);
            if (response == null || !response.path("data").isArray() || !response.path("has_more").isBoolean()) {
                throw new IllegalStateException("Stripe 交易列表格式错误");
            }
            for (JsonNode charge : response.path("data")) {
                after = charge.path("id").asText();
                String paymentNo = charge.path("metadata").path("rlpay_order").asText();
                if (paymentNo.isEmpty() || !"succeeded".equals(charge.path("status").asText())
                        || !charge.path("paid").asBoolean() || !charge.path("captured").asBoolean()) {
                    continue;
                }
                String intent = charge.path("payment_intent").asText();
                long amount = charge.path("amount").longValue();
                Instant created = Instant.ofEpochSecond(charge.path("created").longValue());
                if (!charge.path("livemode").isBoolean() || charge.path("livemode").asBoolean()
                        || !CURRENCY.equals(charge.path("currency").asText()) || !intent.matches("pi_[A-Za-z0-9]+")
                        || !charge.path("amount").isIntegralNumber() || !charge.path("amount").canConvertToLong()
                        || amount <= 0 || created.isBefore(from) || !created.isBefore(to)) {
                    throw new IllegalStateException("Stripe 账单包含无法核实的交易");
                }
                String trade = findSession(intent, paymentNo, amount);
                if (!trades.add(trade)) {
                    throw new IllegalStateException("Stripe 账单存在重复会话");
                }
                csv.append(CODE).append(',').append(trade).append(',').append(amount)
                        .append(",SUCCESS,").append(created).append('\n');
                if (csv.length() > 1024 * 1024) {
                    throw new IllegalStateException("Stripe 支付账单超过导入大小限制");
                }
            }
            if (!response.path("has_more").asBoolean()) {
                return csv.toString();
            }
            if (after == null || !after.matches("ch_[A-Za-z0-9]+") || after.equals(cursor)) {
                throw new IllegalStateException("Stripe 交易分页游标无效");
            }
        }
        throw new IllegalStateException("Stripe 交易账单超过测试分页上限");
    }

    /** 通过 PaymentIntent 查找并验证属于本项目的 Checkout 会话。 */
    private String findSession(String intent, String paymentNo, long amount) {
        JsonNode page = api.get().uri(builder -> builder.path("/v1/checkout/sessions")
                        .queryParam("payment_intent", intent).queryParam("limit", 2).build())
                .retrieve().body(JsonNode.class);
        if (page == null || !page.path("data").isArray() || page.path("data").size() != 1
                || page.path("has_more").asBoolean()) {
            throw new IllegalStateException("Stripe 卡交易没有唯一的 Checkout 会话");
        }
        JsonNode session = page.path("data").get(0);
        String trade = validateSession(session);
        if (!paymentNo.equals(session.path("metadata").path("rlpay_order").asText())
                || !intent.equals(session.path("payment_intent").asText())
                || !"paid".equals(session.path("payment_status").asText())
                || session.path("amount_total").longValue() != amount) {
            throw new IllegalStateException("Stripe 卡交易与 Checkout 会话不一致");
        }
        return trade;
    }

    /** 先验签再解析，仅接受测试模式的完成事件。 */
    public Optional<String> authenticateCompletion(byte[] body, String header) {
        if (!validSignature(body, header)) {
            throw new IllegalArgumentException("Stripe Webhook 签名无效");
        }
        try {
            JsonNode event = json.readTree(body);
            if (event == null || !event.path("livemode").isBoolean() || event.path("livemode").asBoolean()) {
                throw new IllegalArgumentException("仅接受 Stripe 测试事件");
            }
            if (!"checkout.session.completed".equals(event.path("type").asText())) {
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

    /** 确认响应属于测试 Checkout Session 且币种和金额格式符合约定。 */
    private static String validateSession(JsonNode session) {
        if (session == null || !session.path("livemode").isBoolean() || session.path("livemode").asBoolean()
                || !CURRENCY.equals(session.path("currency").asText()) || !session.path("amount_total").isIntegralNumber()
                || !session.path("amount_total").canConvertToLong() || session.path("amount_total").longValue() <= 0
                || !isSession(session.path("id").asText())) {
            throw new IllegalStateException("Stripe 测试会话响应不符合约定");
        }
        return session.path("id").asText();
    }

    /** 检查渠道交易号可安全放入 API 路径。 */
    private static boolean isSession(String tradeNo) {
        return tradeNo != null && SESSION.matcher(tradeNo).matches();
    }

    /** 仅允许不含凭据的 HTTPS URL。 */
    private static URI https(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Stripe 地址必须使用 HTTPS");
        }
        return url;
    }

    /** 创建会话的渠道标识和付款链接。 */
    public record Checkout(String tradeNo, String url) {
    }
}

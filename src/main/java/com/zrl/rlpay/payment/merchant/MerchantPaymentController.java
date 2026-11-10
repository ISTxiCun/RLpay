package com.zrl.rlpay.payment.merchant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zrl.rlpay.payment.service.PaymentAmountConflictException;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.regex.Pattern;

/** 对外商户建单、查单，仅使用认证所得的商户号。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rlpay.merchant-api.enabled", havingValue = "true")
@RequestMapping("/api/merchant/payments")
public class MerchantPaymentController {
    private static final int MAX_BODY = 4096;
    private static final Pattern ORDER_NO = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final MerchantAuthentication authentication;
    private final PaymentOrderService payments;
    private final ObjectMapper json;

    /** 签名绑定原始请求体，金额变化的业务重试返回 409。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public PaymentOrderService.OrderView create(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        String merchant = authenticate(request, body);
        JsonNode input;
        try {
            input = json.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (IOException invalid) {
            throw badInput();
        }
        if (input == null || !input.isObject() || input.size() != 2
                || !input.path("merchantOrderNo").isTextual() || !input.path("amountCent").isIntegralNumber()
                || !input.path("amountCent").canConvertToLong() || input.path("amountCent").longValue() <= 0) {
            throw badInput();
        }
        String orderNo = input.path("merchantOrderNo").textValue();
        if (!ORDER_NO.matcher(orderNo).matches()) {
            throw badInput();
        }
        try {
            return payments.createOrGet(merchant, orderNo, input.path("amountCent").longValue());
        } catch (PaymentAmountConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        }
    }

    /** 按平台单号查单，未知或不属于该商户都返回 404。 */
    @GetMapping("/{paymentOrderNo}")
    public ResponseEntity<PaymentOrderService.OrderView> find(
            @PathVariable String paymentOrderNo, HttpServletRequest request) throws IOException {
        String merchant = authenticate(request, readEmptyBody(request));
        if (!ORDER_NO.matcher(paymentOrderNo).matches() || paymentOrderNo.length() > 64) {
            throw badInput();
        }
        return ResponseEntity.of(payments.findByPaymentOrderNo(merchant, paymentOrderNo));
    }

    /** 按认证商户与商户订单号查单。 */
    @GetMapping("/by-order/{merchantOrderNo}")
    public ResponseEntity<PaymentOrderService.OrderView> findByOrder(
            @PathVariable String merchantOrderNo, HttpServletRequest request) throws IOException {
        String merchant = authenticate(request, readEmptyBody(request));
        if (!ORDER_NO.matcher(merchantOrderNo).matches()) {
            throw badInput();
        }
        return ResponseEntity.of(payments.findByMerchantOrderNo(merchant, merchantOrderNo));
    }

    /** GET 查单只接受空请求体。 */
    private static byte[] readEmptyBody(HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) {
            throw badInput();
        }
        return new byte[0];
    }

    /** 拒绝契约未定义的查询参数，并从头字段提取认证凭证。 */
    private String authenticate(HttpServletRequest request, byte[] body) {
        if (request.getQueryString() != null) {
            throw badInput();
        }
        return authentication.verify(request.getMethod(), request.getRequestURI(), request.getHeader("X-Key-Id"),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), request.getHeader("X-Signature"), body);
    }

    /** 返回统一的订单请求格式错误。 */
    private static ResponseStatusException badInput() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户请求格式无效");
    }
}

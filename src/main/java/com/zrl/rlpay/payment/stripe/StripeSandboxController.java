package com.zrl.rlpay.payment.stripe;

import com.zrl.rlpay.payment.merchant.MerchantAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/** Stripe 测试模式的签名发起和独立验签回调。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rlpay.stripe-sandbox.enabled", havingValue = "true")
public class StripeSandboxController {
    private final StripeWebhookVerifier webhookVerifier;
    private final StripeCheckoutService checkout;
    private final ObjectProvider<MerchantAuthentication> authentication;

    /** 认证商户后为所属平台单发起 Stripe Checkout。 */
    @PostMapping("/api/merchant/payments/{paymentNo}/stripe-checkout")
    public StripeCheckoutService.Launch start(@PathVariable String paymentNo, HttpServletRequest request) throws IOException {
        var auth = authentication.getIfAvailable();
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.getQueryString() != null || request.getInputStream().read() != -1
                || !paymentNo.matches("PAY-[a-f0-9-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String merchant = auth.verify(request.getMethod(), request.getRequestURI(), request.getHeader("X-Key-Id"),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), request.getHeader("X-Signature"),
                new byte[0]);
        return checkout.start(merchant, paymentNo);
    }

    /** 验证 Webhook 原始字节，提前到达时返回可重试状态。 */
    @PostMapping("/api/payment/webhooks/stripe-test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(262_145);
        if (body.length > 262_144) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        java.util.Optional<String> event;
        try {
            event = webhookVerifier.authenticateCompletion(body, request.getHeader("Stripe-Signature"));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe Webhook 验签或格式错误");
        }
        try {
            if (event.isPresent() && !checkout.confirm(event.orElseThrow())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe 结果尚未确认");
            }
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe 暂不可查询");
        }
    }
}

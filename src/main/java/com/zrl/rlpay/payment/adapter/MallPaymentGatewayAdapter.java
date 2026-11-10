package com.zrl.rlpay.payment.adapter;

import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.service.PaymentAmountConflictException;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 将商城的进程内网关契约接到支付模块。 */
@Component
@RequiredArgsConstructor
public class MallPaymentGatewayAdapter implements MallPaymentGateway {
    private final PaymentOrderService orders;

    /** 校验商城商户归属，创建或复用支付单并转换成商城应答。 */
    @Override
    public Response createOrGet(Request request) {
        if (request == null || !MERCHANT_CODE.equals(request.merchantCode())) {
            throw new IllegalArgumentException("商城支付请求必须属于模拟商城商户");
        }

        try {
            PaymentOrderService.OrderView order =
                    orders.createOrGet(MERCHANT_CODE, request.merchantOrderNo(), request.amountCent());
            return new Response(order.paymentOrderNo(), order.status());
        } catch (PaymentAmountConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
    }
}

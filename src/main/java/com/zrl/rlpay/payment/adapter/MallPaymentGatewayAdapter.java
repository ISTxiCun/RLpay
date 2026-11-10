package com.zrl.rlpay.payment.adapter;

import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.payment.service.PaymentAmountConflictException;
import com.zrl.rlpay.payment.service.PaymentChannelService;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 将商城的进程内网关契约接到支付模块。 */
@Component
@RequiredArgsConstructor
public class MallPaymentGatewayAdapter implements MallPaymentGateway {
    private final PaymentOrderService orders;
    private final PaymentChannelService channels;

    /** 校验商城商户归属，创建或复用支付单，再返回渠道发起结果。 */
    @Override
    public Response createOrGet(Request request) {
        if (request == null || !MERCHANT_CODE.equals(request.merchantCode())) {
            throw new IllegalArgumentException("商城支付请求必须属于模拟商城商户");
        }

        try {
            PaymentOrderService.OrderView order =
                    orders.createOrGet(MERCHANT_CODE, request.merchantOrderNo(), request.amountCent());
            var result = channels.launch(order);
            return new Response(order.paymentOrderNo(), result.status(), result.paymentUrl());
        } catch (PaymentAmountConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        } catch (DataAccessResourceFailureException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付服务暂时不可用", unavailable);
        }
    }
}

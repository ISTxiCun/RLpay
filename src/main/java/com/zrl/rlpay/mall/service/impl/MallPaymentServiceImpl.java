package com.zrl.rlpay.mall.service.impl;

import com.zrl.rlpay.mall.service.MallOrderService;
import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.mall.service.MallPaymentService;
import com.zrl.rlpay.mall.vo.MallPaymentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 串联商城的金额冻结、进程内支付调用和支付单号写回。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MallPaymentServiceImpl implements MallPaymentService {
    private final MallOrderService orderService;
    private final ObjectProvider<MallPaymentGateway> paymentGateway;

    /**
     * 确认可调用支付模块后再冻结金额，提交事务并释放商城订单行锁后发起支付。
     * 拿到应答后开启第二个事务写回单号。调用暂时失败时，同一订单可以重试。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MallPaymentVO requestPayment(String merchantOrderNo) {
        MallPaymentGateway gateway = paymentGateway.getIfAvailable();
        if (gateway == null) {
            log.warn("支付模块尚未接入，发起支付返回 503");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付模块尚未接入");
        }
        MallPaymentGateway.Request request = orderService.freezeForPayment(merchantOrderNo);

        MallPaymentGateway.Response response;
        try {
            response = gateway.createOrGet(request);
        } catch (TransientDataAccessException exception) {
            log.warn("支付模块暂时不可用 merchantOrderNo={}", request.merchantOrderNo(), exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付服务暂时不可用", exception);
        }

        orderService.savePaymentOrderNo(request.merchantOrderNo(), response.paymentOrderNo());
        log.info("支付单号已写回 merchantOrderNo={} paymentOrderNo={} status={}",
                request.merchantOrderNo(), response.paymentOrderNo(), response.status());
        return new MallPaymentVO(request, response);
    }
}

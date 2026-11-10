package com.zrl.rlpay.payment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zrl.rlpay.payment.channel.MockPaymentChannel;
import com.zrl.rlpay.payment.entity.PaymentOrder;
import com.zrl.rlpay.payment.mapper.PaymentOrderMapper;
import com.zrl.rlpay.payment.service.PaymentChannelService;
import com.zrl.rlpay.payment.service.PaymentOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 路由已持久化的支付单并保存渠道关联。 */
@Service
@RequiredArgsConstructor
public class PaymentChannelServiceImpl implements PaymentChannelService {
    private final PaymentOrderMapper mapper;
    private final MockPaymentChannel channel;

    /** 在支付事务外发起渠道请求，受理后只允许 CREATED 单绑定一次。 */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LaunchResult launch(PaymentOrderService.OrderView order) {
        PaymentOrder saved = read(order.paymentOrderNo());
        if (!"CREATED".equals(saved.getStatus())) {
            return response(saved);
        }

        var accepted = channel.start(saved.getPaymentOrderNo(), saved.getAmountCent());
        mapper.update(null, Wrappers.lambdaUpdate(PaymentOrder.class)
                .eq(PaymentOrder::getPaymentOrderNo, saved.getPaymentOrderNo())
                .eq(PaymentOrder::getStatus, "CREATED")
                .isNull(PaymentOrder::getChannelCode)
                .isNull(PaymentOrder::getChannelTradeNo)
                .set(PaymentOrder::getChannelCode, MockPaymentChannel.CODE)
                .set(PaymentOrder::getChannelTradeNo, accepted.channelTradeNo())
                .set(PaymentOrder::getStatus, "PENDING"));

        PaymentOrder current = read(saved.getPaymentOrderNo());
        if (!MockPaymentChannel.CODE.equals(current.getChannelCode())
                || !accepted.channelTradeNo().equals(current.getChannelTradeNo())) {
            throw new IllegalStateException("平台支付单的渠道交易关联不一致");
        }
        return response(current);
    }

    /** 供后续回调及对账按渠道和交易号定位平台支付单。 */
    @Override
    public Optional<PaymentOrderService.OrderView> findByChannelTradeNo(String channelCode, String channelTradeNo) {
        PaymentOrder order = mapper.selectOne(Wrappers.lambdaQuery(PaymentOrder.class)
                .eq(PaymentOrder::getChannelCode, channelCode)
                .eq(PaymentOrder::getChannelTradeNo, channelTradeNo));
        return Optional.ofNullable(order).map(value -> new PaymentOrderService.OrderView(
                value.getPaymentOrderNo(), value.getMerchantCode(), value.getMerchantOrderNo(),
                value.getAmountCent(), value.getStatus()));
    }

    /** 从已提交的支付单读取最新状态及渠道关联。 */
    private PaymentOrder read(String paymentOrderNo) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.lambdaQuery(PaymentOrder.class)
                        .eq(PaymentOrder::getPaymentOrderNo, paymentOrderNo)))
                .orElseThrow(() -> new IllegalStateException("平台支付单不存在"));
    }

    /** 待支付时复用已有链接，成功等其他状态只返回当前状态。 */
    private static LaunchResult response(PaymentOrder order) {
        String paymentUrl = "PENDING".equals(order.getStatus())
                ? MockPaymentChannel.paymentUrl(order.getChannelTradeNo()) : null;
        return new LaunchResult(order.getStatus(), paymentUrl);
    }
}

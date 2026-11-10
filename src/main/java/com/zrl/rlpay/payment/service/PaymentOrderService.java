package com.zrl.rlpay.payment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zrl.rlpay.payment.entity.PaymentOrder;
import com.zrl.rlpay.payment.mapper.PaymentOrderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/** 支付单内部服务。商户范围由受信的进程内调用方提供。 */
@Service
public class PaymentOrderService {
    private static final int MERCHANT_CODE_MAX_LENGTH = 64;
    private static final int MERCHANT_ORDER_NO_MAX_LENGTH = 128;
    private static final int PAYMENT_ORDER_NO_MAX_LENGTH = 64;
    private static final String CREATED = "CREATED";

    private final PaymentOrderMapper mapper;
    private final TransactionTemplate independentTransaction;

    /** 使用独立事务保存支付单，冲突后也能从新事务读到已提交的原单。 */
    public PaymentOrderService(PaymentOrderMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 按可信商户和商户订单号创建支付单，或复用同金额原单。 */
    public OrderView createOrGet(String trustedMerchantCode, String merchantOrderNo, long amountCent) {
        requireIdentifier(trustedMerchantCode, MERCHANT_CODE_MAX_LENGTH, "商户号");
        requireIdentifier(merchantOrderNo, MERCHANT_ORDER_NO_MAX_LENGTH, "商户订单号");
        if (amountCent <= 0) {
            throw new IllegalArgumentException("支付金额必须大于 0 分");
        }

        PaymentOrder candidate = new PaymentOrder();
        candidate.setPaymentOrderNo("PAY-" + UUID.randomUUID());
        candidate.setMerchantCode(trustedMerchantCode);
        candidate.setMerchantOrderNo(merchantOrderNo);
        candidate.setAmountCent(amountCent);
        candidate.setStatus(CREATED);

        try {
            independentTransaction.executeWithoutResult(transaction -> mapper.insert(candidate));
            return toView(candidate);
        } catch (DuplicateKeyException collision) {
            // 事务已结束。只有同商户的同一订单存在时，才把唯一键冲突当作业务重试。
            OrderView existing = independentTransaction.execute(transaction ->
                    findByMerchantOrderNo(trustedMerchantCode, merchantOrderNo).orElseThrow(() -> collision));
            if (existing.amountCent() != amountCent) {
                throw new PaymentAmountConflictException();
            }
            return existing;
        }
    }

    /** 按可信商户和平台支付单号查询。其他商户的单与不存在的单均返回空。 */
    public Optional<OrderView> findByPaymentOrderNo(String trustedMerchantCode, String paymentOrderNo) {
        requireIdentifier(trustedMerchantCode, MERCHANT_CODE_MAX_LENGTH, "商户号");
        requireIdentifier(paymentOrderNo, PAYMENT_ORDER_NO_MAX_LENGTH, "平台支付单号");
        PaymentOrder order = mapper.selectOne(Wrappers.lambdaQuery(PaymentOrder.class)
                .eq(PaymentOrder::getMerchantCode, trustedMerchantCode)
                .eq(PaymentOrder::getPaymentOrderNo, paymentOrderNo));
        return Optional.ofNullable(order).map(PaymentOrderService::toView);
    }

    /** 按可信商户和商户订单号查询。其他商户的单与不存在的单均返回空。 */
    public Optional<OrderView> findByMerchantOrderNo(String trustedMerchantCode, String merchantOrderNo) {
        requireIdentifier(trustedMerchantCode, MERCHANT_CODE_MAX_LENGTH, "商户号");
        requireIdentifier(merchantOrderNo, MERCHANT_ORDER_NO_MAX_LENGTH, "商户订单号");
        PaymentOrder order = mapper.selectOne(Wrappers.lambdaQuery(PaymentOrder.class)
                .eq(PaymentOrder::getMerchantCode, trustedMerchantCode)
                .eq(PaymentOrder::getMerchantOrderNo, merchantOrderNo));
        return Optional.ofNullable(order).map(PaymentOrderService::toView);
    }

    /** 在写入或查库前拒绝空标识和超长标识，保持标识原样比较。 */
    private static void requireIdentifier(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过 " + maxLength);
        }
    }

    /** 将持久化实体转成内部调用方可使用的只读结果。 */
    private static OrderView toView(PaymentOrder order) {
        return new OrderView(order.getPaymentOrderNo(), order.getMerchantCode(), order.getMerchantOrderNo(),
                order.getAmountCent(), order.getStatus());
    }

    /** 支付单的内部查询和创建结果。 */
    public record OrderView(String paymentOrderNo, String merchantCode, String merchantOrderNo,
                            long amountCent, String status) {
    }
}

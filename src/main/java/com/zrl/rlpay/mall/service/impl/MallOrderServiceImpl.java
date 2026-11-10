package com.zrl.rlpay.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zrl.rlpay.mall.dto.MallOrderRequest;
import com.zrl.rlpay.mall.entity.MallOrder;
import com.zrl.rlpay.mall.entity.MallOrderItem;
import com.zrl.rlpay.mall.entity.MallOrderStatus;
import com.zrl.rlpay.mall.entity.MallProduct;
import com.zrl.rlpay.mall.mapper.MallOrderItemMapper;
import com.zrl.rlpay.mall.mapper.MallOrderMapper;
import com.zrl.rlpay.mall.mapper.MallProductMapper;
import com.zrl.rlpay.mall.service.MallOrderService;
import com.zrl.rlpay.mall.service.MallPaymentGateway;
import com.zrl.rlpay.mall.vo.MallOrderVO;
import com.zrl.rlpay.mall.vo.MallProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 维护商城商品查询、订单状态和订单明细。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MallOrderServiceImpl implements MallOrderService {

    private final MallProductMapper productMapper;
    private final MallOrderMapper orderMapper;
    private final MallOrderItemMapper itemMapper;

    /** 按主键顺序列出可下单商品。 */
    @Override
    public List<MallProductVO> listProducts() {
        return productMapper.selectList(Wrappers.lambdaQuery(MallProduct.class)
                        .eq(MallProduct::isEnabled, true)
                        .orderByAsc(MallProduct::getId))
                .stream()
                .map(product -> new MallProductVO(product.getProductCode(), product.getName(), product.getPriceCent()))
                .toList();
    }

    /** 生成商户订单号，在同一事务中保存草稿和商品快照。 */
    @Override
    @Transactional
    public MallOrderVO createOrder(MallOrderRequest.Create request) {
        OrderLines lines = buildOrderItems(request.items());
        MallOrder order = new MallOrder();
        order.setMerchantOrderNo("MALL-" + UUID.randomUUID());
        order.setStatus(MallOrderStatus.DRAFT);
        order.setTotalAmountCent(lines.totalAmountCent());
        orderMapper.insert(order);
        insertItems(order.getId(), lines.items());
        log.info("写入订单草稿 merchantOrderNo={} amountCent={}", order.getMerchantOrderNo(), order.getTotalAmountCent());
        return orderDetails(order);
    }

    /** 在同一数据库快照中读取订单和明细，避免并发修改时读到不同版本的数据。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MallOrderVO getOrder(String merchantOrderNo) {
        return orderDetails(findOrder(merchantOrderNo, false));
    }

    /** 锁定草稿并核对版本，再按当前商品价格重算总金额、替换明细。 */
    @Override
    @Transactional
    public MallOrderVO updateOrder(String merchantOrderNo, MallOrderRequest.Update request) {
        MallOrder order = findOrder(merchantOrderNo, true);
        requireDraft(order);
        if (order.getVersion() != request.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单版本已过期");
        }

        OrderLines lines = buildOrderItems(request.items());
        itemMapper.delete(Wrappers.lambdaQuery(MallOrderItem.class).eq(MallOrderItem::getOrderId, order.getId()));
        insertItems(order.getId(), lines.items());
        order.setTotalAmountCent(lines.totalAmountCent());
        updateOrderRow(order);
        log.info("更新订单草稿 merchantOrderNo={} version={} amountCent={}",
                order.getMerchantOrderNo(), order.getVersion(), order.getTotalAmountCent());
        return orderDetails(order);
    }

    /** 锁定草稿并标记为已取消，保留订单和明细供查询。 */
    @Override
    @Transactional
    public void cancelOrder(String merchantOrderNo) {
        MallOrder order = findOrder(merchantOrderNo, true);
        if (order.getStatus() == MallOrderStatus.CANCELLED) {
            return;
        }

        requireDraft(order);
        order.setStatus(MallOrderStatus.CANCELLED);
        updateOrderRow(order);
        log.info("写入订单取消状态 merchantOrderNo={}", order.getMerchantOrderNo());
    }

    /** 在支付前的事务中冻结草稿金额，重试时沿用之前冻结的金额。 */
    @Override
    @Transactional
    public MallPaymentGateway.Request freezeForPayment(String merchantOrderNo) {
        MallOrder order = findOrder(merchantOrderNo, true);
        switch (order.getStatus()) {
            case DRAFT -> {
                order.setStatus(MallOrderStatus.PAYMENT_REQUESTED);
                updateOrderRow(order);
            }
            case PAYMENT_REQUESTED, PAID -> {
                // 已发起或已支付的订单重试时沿用冻结金额。
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可发起支付");
        }
        return new MallPaymentGateway.Request(MallPaymentGateway.MERCHANT_CODE, order.getMerchantOrderNo(), order.getTotalAmountCent());
    }

    /** 在支付后的事务中写回支付单号。并发重试时校验单号一致。 */
    @Override
    @Transactional
    public void savePaymentOrderNo(String merchantOrderNo, String paymentOrderNo) {
        MallOrder order = findOrder(merchantOrderNo, true);
        if (order.getPaymentOrderNo() == null) {
            order.setPaymentOrderNo(paymentOrderNo);
            updateOrderRow(order);
        } else if (!order.getPaymentOrderNo().equals(paymentOrderNo)) {
            throw new IllegalStateException("支付模块返回的单号与商城已记录的单号不一致");
        }
    }

    /** 锁定订单后校验通知，支付单号和已支付状态一同写入。 */
    @Override
    @Transactional
    public void confirmPayment(PaymentConfirmation confirmation) {
        if (confirmation == null || !MallPaymentGateway.MERCHANT_CODE.equals(confirmation.merchantCode())
                || confirmation.merchantOrderNo() == null || confirmation.merchantOrderNo().isBlank()
                || confirmation.paymentOrderNo() == null || confirmation.paymentOrderNo().isBlank()
                || confirmation.paymentOrderNo().length() > 64 || confirmation.amountCent() <= 0) {
            throw new IllegalArgumentException("无效的商城支付成功通知");
        }
        MallOrder order = findOrder(confirmation.merchantOrderNo(), true);
        if (!order.getMerchantOrderNo().equals(confirmation.merchantOrderNo())
                || order.getTotalAmountCent() != confirmation.amountCent()
                || (order.getPaymentOrderNo() != null
                    && !order.getPaymentOrderNo().equals(confirmation.paymentOrderNo()))) {
            throw new IllegalStateException("支付成功通知与商城订单不匹配");
        }
        if (order.getStatus() == MallOrderStatus.PAID && order.getPaymentOrderNo() != null) {
            return;
        }
        if (order.getStatus() != MallOrderStatus.PAYMENT_REQUESTED) {
            throw new IllegalStateException("商城订单当前状态不接受支付成功通知");
        }
        order.setPaymentOrderNo(confirmation.paymentOrderNo());
        order.setStatus(MallOrderStatus.PAID);
        updateOrderRow(order);
    }

    /** 按商户订单号查单。写操作用行锁串行处理同一订单。 */
    private MallOrder findOrder(String merchantOrderNo, boolean forUpdate) {
        var query = Wrappers.lambdaQuery(MallOrder.class)
                .eq(MallOrder::getMerchantOrderNo, merchantOrderNo)
                .last(forUpdate, "FOR UPDATE");
        MallOrder order = orderMapper.selectOne(query);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /** 修改和取消之前检查订单仍处于草稿状态。 */
    private void requireDraft(MallOrder order) {
        if (order.getStatus() != MallOrderStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿订单可以修改或取消");
        }
    }

    /** 持有订单行锁时保存修改，并递增版本以识别过期的编辑请求。 */
    private void updateOrderRow(MallOrder order) {
        order.setVersion(order.getVersion() + 1);
        orderMapper.updateById(order);
    }

    /** 批量校验商品，记录名称和单价快照，并计算精确到分的总金额。 */
    private OrderLines buildOrderItems(List<MallOrderRequest.Item> requestedItems) {
        Set<String> productCodes = new HashSet<>();
        for (MallOrderRequest.Item item : requestedItems) {
            if (!productCodes.add(item.productCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一商品只能出现一次");
            }
        }

        Map<String, MallProduct> products = productMapper.selectList(Wrappers.lambdaQuery(MallProduct.class)
                        .in(MallProduct::getProductCode, productCodes))
                .stream()
                .collect(Collectors.toMap(MallProduct::getProductCode, Function.identity()));

        List<MallOrderItem> lines = new ArrayList<>(requestedItems.size());
        long total = 0;
        for (MallOrderRequest.Item requested : requestedItems) {
            MallProduct product = products.get(requested.productCode());
            if (product == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在：" + requested.productCode());
            }
            if (!product.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已停用：" + requested.productCode());
            }

            MallOrderItem line = new MallOrderItem();
            line.setProductId(product.getId());
            line.setProductName(product.getName());
            line.setUnitPriceCent(product.getPriceCent());
            line.setQuantity(requested.quantity());
            lines.add(line);
            try {
                total = Math.addExact(total, Math.multiplyExact(line.getUnitPriceCent(), line.getQuantity()));
            } catch (ArithmeticException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单金额超出支持范围", exception);
            }
        }
        return new OrderLines(lines, total);
    }

    /** 将已计算的明细关联到订单主键，在调用方事务中逐条写入。 */
    private void insertItems(Long orderId, List<MallOrderItem> items) {
        for (MallOrderItem item : items) {
            item.setOrderId(orderId);
            itemMapper.insert(item);
        }
    }

    /** 组装订单详情，从商品表取稳定编码，从明细表取下单时的名称和单价。 */
    private MallOrderVO orderDetails(MallOrder order) {
        List<MallOrderItem> items = itemMapper.selectList(Wrappers.lambdaQuery(MallOrderItem.class)
                .eq(MallOrderItem::getOrderId, order.getId())
                .orderByAsc(MallOrderItem::getId));
        List<Long> productIds = items.stream().map(MallOrderItem::getProductId).toList();
        Map<Long, String> productCodes = productIds.isEmpty() ? Map.of() : productMapper.selectByIds(productIds)
                .stream()
                .collect(Collectors.toMap(MallProduct::getId, MallProduct::getProductCode));
        List<MallOrderVO.Item> details = items.stream()
                .map(item -> new MallOrderVO.Item(productCodes.get(item.getProductId()),
                        item.getProductName(), item.getUnitPriceCent(), item.getQuantity()))
                .toList();
        return new MallOrderVO(order.getMerchantOrderNo(), order.getStatus(), order.getVersion(),
                order.getTotalAmountCent(), order.getPaymentOrderNo(), details);
    }

    private record OrderLines(List<MallOrderItem> items, long totalAmountCent) {
    }
}

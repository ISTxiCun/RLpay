package com.zrl.rlpay.mall.vo;

import com.zrl.rlpay.mall.entity.MallOrderStatus;

import java.util.List;

/** 商城 HTTP 接口返回的订单详情。 */
public record MallOrderVO(
        String merchantOrderNo,
        MallOrderStatus status,
        int version,
        long totalAmountCent,
        String paymentOrderNo,
        List<Item> items
) {
    public record Item(String productCode, String productName, long unitPriceCent, int quantity) {
    }
}

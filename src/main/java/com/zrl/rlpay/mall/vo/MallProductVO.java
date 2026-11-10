package com.zrl.rlpay.mall.vo;

/** 商城商品目录中的一项，价格以分表示。 */
public record MallProductVO(String productCode, String name, long priceCent) {
}

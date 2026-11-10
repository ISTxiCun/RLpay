package com.zrl.rlpay.mall.entity;

public enum MallOrderStatus {
    /** 草稿，可以修改或取消。 */
    DRAFT,
    /** 商城已冻结金额，支付模块可能尚未收到请求。 */
    PAYMENT_REQUESTED,
    /** 草稿已取消，仍可查询。 */
    CANCELLED,
    /** 商城已确认支付成功。 */
    PAID
}

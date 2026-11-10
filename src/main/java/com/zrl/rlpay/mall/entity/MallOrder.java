package com.zrl.rlpay.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("mall_order")
public class MallOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String merchantOrderNo;
    private MallOrderStatus status;
    private long totalAmountCent;
    private String paymentOrderNo;
    private int version;
}

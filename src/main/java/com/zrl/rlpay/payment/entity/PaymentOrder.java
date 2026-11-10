package com.zrl.rlpay.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 支付模块维护的平台支付单。 */
@Getter
@Setter
@TableName("payment_order")
public class PaymentOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentOrderNo;
    private String merchantCode;
    private String merchantOrderNo;
    private Long amountCent;
    private String status;
    private String channelCode;
    private String channelTradeNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

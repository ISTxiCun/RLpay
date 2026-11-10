package com.zrl.rlpay.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("mall_product")
public class MallProduct {
    private Long id;
    private String productCode;
    private String name;
    private long priceCent;
    private boolean enabled;
}

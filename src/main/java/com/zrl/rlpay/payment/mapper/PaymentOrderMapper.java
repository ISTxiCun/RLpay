package com.zrl.rlpay.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zrl.rlpay.payment.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;

/** 支付单的数据库读写入口。 */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {
}

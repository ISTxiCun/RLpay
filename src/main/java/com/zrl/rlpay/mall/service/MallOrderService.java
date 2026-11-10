package com.zrl.rlpay.mall.service;

import com.zrl.rlpay.mall.dto.MallOrderRequest;
import com.zrl.rlpay.mall.vo.MallOrderVO;
import com.zrl.rlpay.mall.vo.MallProductVO;

import java.util.List;

/** 商城商品和订单操作的服务接口。 */
public interface MallOrderService {
    /** 查询可下单的商品。 */
    List<MallProductVO> listProducts();

    /** 根据所选商品和数量创建草稿订单。 */
    MallOrderVO createOrder(MallOrderRequest.Create request);

    /** 按商户订单号查询订单详情。 */
    MallOrderVO getOrder(String merchantOrderNo);

    /** 校验草稿版本并替换订单明细。 */
    MallOrderVO updateOrder(String merchantOrderNo, MallOrderRequest.Update request);

    /** 取消草稿并保留订单记录。 */
    void cancelOrder(String merchantOrderNo);

    /** 冻结订单金额并生成给支付模块的请求。 */
    MallPaymentGateway.Request freezeForPayment(String merchantOrderNo);

    /** 将支付模块返回的支付单号写回商城订单。 */
    void savePaymentOrderNo(String merchantOrderNo, String paymentOrderNo);
}

package com.zrl.rlpay.mall.controller;

import com.zrl.rlpay.mall.dto.MallOrderRequest;
import com.zrl.rlpay.mall.service.MallOrderService;
import com.zrl.rlpay.mall.service.MallPaymentService;
import com.zrl.rlpay.mall.vo.MallOrderVO;
import com.zrl.rlpay.mall.vo.MallPaymentVO;
import com.zrl.rlpay.mall.vo.MallProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模拟商城的 HTTP 入口，负责接收请求并交给对应的业务服务。 */
@Tag(name = "模拟商城", description = "商品查询、商城订单和支付请求")
@RestController
@RequestMapping("/api/mall")
@RequiredArgsConstructor
public class MallController {
    private final MallOrderService orderService;
    private final MallPaymentService paymentService;

    /** 查询已启用的商品目录，价格以分返回。 */
    @Operation(summary = "查询商品列表")
    @GetMapping("/products")
    public List<MallProductVO> listProducts() {
        return orderService.listProducts();
    }

    /** 创建草稿订单，由服务端按商品当前价格计算总金额。 */
    @Operation(summary = "创建订单")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public MallOrderVO createOrder(@RequestBody @Valid MallOrderRequest.Create request) {
        return orderService.createOrder(request);
    }

    /** 按商户订单号查询订单和明细，已取消订单也可以查回。 */
    @Operation(summary = "查询订单")
    @GetMapping("/orders/{merchantOrderNo}")
    public MallOrderVO getOrder(@PathVariable String merchantOrderNo) {
        return orderService.getOrder(merchantOrderNo);
    }

    /** 按请求中的版本修改草稿，使用本次提交的明细替换原明细。 */
    @Operation(summary = "修改订单")
    @PutMapping("/orders/{merchantOrderNo}")
    public MallOrderVO updateOrder(@PathVariable String merchantOrderNo,
                                   @RequestBody @Valid MallOrderRequest.Update request) {
        return orderService.updateOrder(merchantOrderNo, request);
    }

    /** 将草稿标记为已取消。重复取消同一订单也返回 204。 */
    @Operation(summary = "取消订单")
    @DeleteMapping("/orders/{merchantOrderNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable String merchantOrderNo) {
        orderService.cancelOrder(merchantOrderNo);
    }

    /** 发起支付，返回商城交给支付模块的参数和支付模块的应答。 */
    @Operation(summary = "发起支付")
    @PostMapping("/orders/{merchantOrderNo}/payment")
    public MallPaymentVO requestPayment(@PathVariable String merchantOrderNo) {
        return paymentService.requestPayment(merchantOrderNo);
    }
}

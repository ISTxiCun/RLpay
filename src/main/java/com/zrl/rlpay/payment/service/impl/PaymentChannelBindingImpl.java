package com.zrl.rlpay.payment.service.impl;

import com.zrl.rlpay.payment.service.PaymentChannelBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 条件绑定，拒绝覆盖其他渠道或其他平台单的交易。 */
@Service
@RequiredArgsConstructor
public class PaymentChannelBindingImpl implements PaymentChannelBinding {
    private final JdbcTemplate jdbc;

    /** 首次绑定只允许 CREATED；相同绑定重复调用只读取当前状态。 */
    @Override
    @Transactional
    public String bind(String merchantCode, String paymentNo, String channelCode, String tradeNo) {
        if (channelCode == null || channelCode.isBlank() || channelCode.length() > 32
                || tradeNo == null || tradeNo.isBlank() || tradeNo.length() > 255) {
            throw new IllegalArgumentException("渠道编码或交易号无效");
        }
        try {
            jdbc.update("""
                    UPDATE payment_order SET status = 'PENDING', channel_code = ?, channel_trade_no = ?
                    WHERE payment_order_no = ? AND merchant_code = ? AND status = 'CREATED'
                      AND channel_code IS NULL AND channel_trade_no IS NULL
                    """, channelCode, tradeNo, paymentNo, merchantCode);
        } catch (DuplicateKeyException duplicate) {
            throw new Conflict();
        }
        return jdbc.query("""
                SELECT status FROM payment_order WHERE payment_order_no = ? AND merchant_code = ?
                  AND channel_code = ? AND channel_trade_no = ? FOR UPDATE
                """, (row, index) -> row.getString(1), paymentNo, merchantCode, channelCode, tradeNo)
                .stream().findFirst().orElseThrow(Conflict::new);
    }
}

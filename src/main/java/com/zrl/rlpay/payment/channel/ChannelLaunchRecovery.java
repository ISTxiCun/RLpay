package com.zrl.rlpay.payment.channel;

/** 由渠道适配器恢复尚未完成的平台绑定。 */
public interface ChannelLaunchRecovery {
    /** 扫描一批本渠道已持久化且到期的发起记录。 */
    void recoverDue();
}

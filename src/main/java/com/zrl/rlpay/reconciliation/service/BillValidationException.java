package com.zrl.rlpay.reconciliation.service;

/** 账单或批次元数据不合法，行号为 CSV 的物理行号。 */
public class BillValidationException extends RuntimeException {
    private final Integer line;

    /** 文件整体错误用空行号，行内错误使用从 1 开始的行号。 */
    public BillValidationException(Integer line, String message) {
        super(message);
        this.line = line;
    }

    /** 获取可返回给导入人员的物理行号。 */
    public Integer getLine() {
        return line;
    }
}

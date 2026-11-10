-- 在目标库中手动执行。本脚本仅初始化模拟商城模块。

-- ============================================================
-- 1. 商品表（mall_product）
-- 保存商城商品目录。只有启用的商品可以下单。
-- ============================================================
CREATE TABLE IF NOT EXISTS mall_product (
    id BIGINT NOT NULL AUTO_INCREMENT,           -- 商品主键
    product_code VARCHAR(32) NOT NULL,           -- 商品编码，供下单接口使用
    name VARCHAR(128) NOT NULL,                  -- 商品名称
    price_cent BIGINT NOT NULL,                  -- 当前单价，单位为分，例如 17.90 元存 1790
    enabled BOOLEAN NOT NULL DEFAULT TRUE,       -- 是否允许下单
    PRIMARY KEY (id),
    UNIQUE KEY uk_mall_product_code (product_code),
    CHECK (price_cent > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================================================
-- 2. 订单主表（mall_order）
-- 一行代表一笔商城业务订单，记录总金额、状态和关联的支付单号。
-- 状态：DRAFT 草稿，PAYMENT_REQUESTED 已冻结金额并开始发起支付，
--       CANCELLED 已取消，PAID 已确认支付成功。
-- ============================================================
CREATE TABLE IF NOT EXISTS mall_order (
    id BIGINT NOT NULL AUTO_INCREMENT,           -- 订单主键，供订单明细关联
    merchant_order_no VARCHAR(64) NOT NULL,      -- 商城生成的唯一订单号，发起支付时传给支付模块
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', -- 当前订单状态
    total_amount_cent BIGINT NOT NULL,           -- 各明细单价乘数量后的合计，单位为分
    payment_order_no VARCHAR(64) DEFAULT NULL,   -- 支付模块返回的支付单号，收到前为空
    version INT NOT NULL DEFAULT 0,              -- 订单版本，用于识别基于旧版本提交的修改
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), -- 创建时间
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), -- 最近更新时间
    PRIMARY KEY (id),
    UNIQUE KEY uk_mall_order_no (merchant_order_no),
    UNIQUE KEY uk_mall_order_payment_no (payment_order_no),
    CHECK (status IN ('DRAFT', 'PAYMENT_REQUESTED', 'CANCELLED', 'PAID')),
    CHECK (total_amount_cent > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================================================
-- 3. 订单明细表（mall_order_item）
-- 一行记录一笔订单中的一种商品，保存当时的商品名称和成交单价。
-- ============================================================
CREATE TABLE IF NOT EXISTS mall_order_item (
    id BIGINT NOT NULL AUTO_INCREMENT,           -- 明细主键
    order_id BIGINT NOT NULL,                    -- 对应 mall_order.id
    product_id BIGINT NOT NULL,                  -- 对应 mall_product.id
    product_name VARCHAR(128) NOT NULL,          -- 商品名称快照，查询历史订单时使用
    unit_price_cent BIGINT NOT NULL,             -- 商品单价快照，单位为分
    quantity INT NOT NULL,                       -- 购买件数，允许 1 至 99 件
    PRIMARY KEY (id),
    -- 同一订单中的同一种商品只占一行，件数记在 quantity。
    UNIQUE KEY uk_mall_order_item_product (order_id, product_id),
    CONSTRAINT fk_mall_order_item_order FOREIGN KEY (order_id) REFERENCES mall_order (id),
    CONSTRAINT fk_mall_order_item_product FOREIGN KEY (product_id) REFERENCES mall_product (id),
    CHECK (unit_price_cent > 0),
    CHECK (quantity BETWEEN 1 AND 99)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================================================
-- 4. 模拟商品数据
-- 预置三款可下单商品。重复执行时保留已有商品的名称、价格和启用状态。
-- ============================================================
INSERT INTO mall_product (product_code, name, price_cent, enabled)
VALUES ('MALL-NOTEBOOK', '方格笔记本', 1290, TRUE),
       ('MALL-MUG', '陶瓷马克杯', 2590, TRUE),
       ('MALL-PEN', '黑色签字笔', 390, TRUE)
ON DUPLICATE KEY UPDATE id = mall_product.id;

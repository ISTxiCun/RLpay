-- 第 4 步已确认的存量支付单也需要通知商城，投递时由商城校验并幂等处理。
INSERT INTO payment_notification
    (payment_order_no, event_type, merchant_code, merchant_order_no, amount_cent, next_attempt_at_utc)
SELECT p.payment_order_no, 'PAYMENT_SUCCEEDED', p.merchant_code, p.merchant_order_no, p.amount_cent, UTC_TIMESTAMP(3)
FROM payment_order p
WHERE p.status = 'SUCCESS'
  AND NOT EXISTS (
      SELECT 1 FROM payment_notification n
      WHERE n.payment_order_no = p.payment_order_no AND n.event_type = 'PAYMENT_SUCCEEDED'
  );

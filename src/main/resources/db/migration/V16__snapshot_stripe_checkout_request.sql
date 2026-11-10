-- 保存首次发送给 Stripe 的表单。历史记录没有可靠的原参数，保留 NULL 供人工核实。
ALTER TABLE stripe_checkout_attempt ADD COLUMN request_form MEDIUMTEXT NULL;

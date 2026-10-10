-- ADD_ITEM explicitly rejected these requests before creating an item. Preserve
-- the immutable request and error history, but release the duplicate-create guard.
UPDATE product_publish_workflows
SET publication_attempted = FALSE,
    publication_target_key = NULL,
    error_message = 'Shopee 已拒绝创建商品：缺少品牌信息。已解除旧任务锁，请使用默认无品牌后重新提交。',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE status = 'FAILED'
  AND current_step = 'PUBLISH'
  AND destination_platform = 'SHOPEE'
  AND publish_audit_id IS NULL
  AND error_message = '外部平台创建请求可能已经发出，系统已停止自动重试；请先到目标店铺核查：Shopee 官方接口 /api/v2/product/add_item 失败：Brand information required';

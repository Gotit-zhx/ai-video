-- Official GET_ITEM_BASE_INFO verified item 846055663 as SELLER_DELETE.
-- Scope to the exact failed tenant/shop/workflow; retain original request and history.
UPDATE product_publish_workflows
SET publication_attempted=FALSE, publication_target_key=NULL,
 error_message='平台已确认旧商品 846055663 为 SELLER_DELETE，已解除上传锁，可重新提交。', updated_at=CURRENT_TIMESTAMP(6)
WHERE id='c49e3a95-99a0-4600-bb3a-f2f35a5205d2'
 AND organization_id='4d6279a0-92b2-4a26-9edf-3bfe5b58f817'
 AND shop_id='4739be25-0900-4ff8-94b6-3d76aba22492'
 AND status='FAILED' AND publish_audit_id IS NULL;

-- Official unlist succeeded and GET_ITEM_BASE_INFO confirmed UNLIST.
UPDATE product_publish_audits
SET response_json=JSON_SET(response_json,'$.remoteStatus','UNLIST','$.listingMode','PENDING_LISTING')
WHERE id='190ea124-ab4f-4020-8685-055894bc72ab'
 AND organization_id='4d6279a0-92b2-4a26-9edf-3bfe5b58f817'
 AND external_id='846055666' AND status='PUBLISHED';

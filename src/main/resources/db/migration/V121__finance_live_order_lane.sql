-- Keep historical ORDER cursors intact. Current orders get their own lease,
-- checkpoint and retry backoff, so history can no longer block today's updates.
INSERT IGNORE INTO finance_sync_states(organization_id,user_id,shop_id,channel,next_at)
SELECT organization_id,user_id,id,'ORDER_LIVE',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)
FROM finance_connections WHERE enabled=TRUE AND status='ACTIVE';

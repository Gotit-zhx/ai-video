-- idx_finance_reconcile_tx begins with all five columns of idx_finance_bill,
-- and also covers source/currency/amount. No unique constraint is removed.
-- Production may already have applied the verified online storage operation.
SET @finance_bill_index_exists = (
 SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='finance_records'
 AND index_name='idx_finance_bill' AND seq_in_index=1
);
SET @finance_drop_bill_index = IF(@finance_bill_index_exists=1,
 'ALTER TABLE finance_records DROP INDEX idx_finance_bill, ALGORITHM=INPLACE, LOCK=NONE',
 'SELECT 1');
PREPARE finance_drop_bill FROM @finance_drop_bill_index;
EXECUTE finance_drop_bill;
DEALLOCATE PREPARE finance_drop_bill;

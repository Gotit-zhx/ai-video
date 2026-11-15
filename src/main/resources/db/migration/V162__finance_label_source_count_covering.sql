ALTER TABLE finance_label_invoice_rows
 ADD INDEX idx_label_import_shop_status (import_id,matched_shop_id,match_status),
 ALGORITHM=INPLACE, LOCK=NONE;

-- The export status can prove that an ordinary ledger range has no corrected FX dates.
-- Split these operations so the index build can use LOCK=NONE.
ALTER TABLE finance_records
  ADD COLUMN fx_month_corrected BOOLEAN GENERATED ALWAYS AS
    (JSON_EXTRACT(raw_json,'$._finance_reporting_month') IS NOT NULL) VIRTUAL INVISIBLE,
  ALGORITHM=INSTANT;

ALTER TABLE finance_records
  ADD INDEX idx_finance_fx_month_corrected (organization_id,fx_month_corrected,record_date) INVISIBLE,
  ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE finance_dashboard_ledger_values
 ADD COLUMN imported_bill BOOLEAN NOT NULL DEFAULT FALSE,
 ALGORITHM=INSTANT;

-- The older totals omit imported bill headers. Rebuild the derived days without editing the source ledger.
UPDATE finance_dashboard_ledger_days SET ready=FALSE WHERE ready;

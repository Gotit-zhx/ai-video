ALTER TABLE commerce_design_generation_consents
 ADD COLUMN model_configuration_sha256 CHAR(64) NULL AFTER confirmed_at;
-- Existing confirmations have no recoverable configuration snapshot. Keep NULL;
-- unfinished generations must use a new design instead of assuming equivalence.

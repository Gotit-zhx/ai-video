-- Refresh the derived groups using exact source bytes. Per-order coverage remains valid.
UPDATE finance_dashboard_order_days SET groups_ready=FALSE WHERE groups_ready;

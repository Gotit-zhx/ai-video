ALTER TABLE finance_qianyi_returns
    ADD COLUMN source_received_at BIGINT GENERATED ALWAYS AS (
        CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.receiveTime')) REGEXP '^[0-9]{12,13}$'
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.receiveTime')) AS UNSIGNED) >= 946684800000
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.receiveTime')) AS UNSIGNED) < 4102444800000
        THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.receiveTime')) AS UNSIGNED) ELSE NULL END
    ) VIRTUAL,
    ADD COLUMN return_receipt_state VARCHAR(24) GENERATED ALWAYS AS (
        CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(data_json,'$._returnReceipt.source'))='QIANYI_TRACKING'
          THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$._returnReceipt.state')),'UNKNOWN')
          ELSE 'UNKNOWN' END
    ) VIRTUAL,
    ADD INDEX idx_qianyi_receipt_month (organization_id,source_received_at,shop_id),
    ADD INDEX idx_qianyi_receipt_qualified (organization_id,return_receipt_state,return_state,source_received_at,shop_id);

ALTER TABLE finance_qianyi_connections ADD COLUMN last_receipt_scan_at DATETIME(6) NULL;

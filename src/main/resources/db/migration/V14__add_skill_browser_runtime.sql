ALTER TABLE automation_capabilities
    ADD COLUMN runtime_config JSON NULL AFTER input_schema;

UPDATE automation_capabilities
SET runtime_config = CASE
    WHEN code = 'YUE_BILL_EXPORT' THEN JSON_OBJECT(
        'browserEngine', 'FEIKUA',
        'proxyPolicy', 'DIRECT',
        'allowedDomains', JSON_ARRAY()
    )
    ELSE JSON_OBJECT(
        'browserEngine', 'BROWSER_ACT',
        'browserType', 'chrome',
        'browserId', '',
        'proxyPolicy', 'DIRECT',
        'requiredRegion', '',
        'allowedDomains', JSON_ARRAY()
    )
END;

ALTER TABLE automation_capabilities
    MODIFY COLUMN runtime_config JSON NOT NULL;

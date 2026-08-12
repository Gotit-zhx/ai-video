UPDATE automation_capabilities
SET input_schema = JSON_OBJECT(
        'version', 1,
        'fields', JSON_ARRAY(),
        'prerequisites', JSON_ARRAY()
    ),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE code = 'YUE_BILL_EXPORT';

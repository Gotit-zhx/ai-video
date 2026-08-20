ALTER TABLE ai_video_generation_jobs
    ADD COLUMN submit_request_json LONGTEXT NULL AFTER error_message,
    ADD COLUMN submit_response_json LONGTEXT NULL AFTER submit_request_json,
    ADD COLUMN query_request_json LONGTEXT NULL AFTER submit_response_json,
    ADD COLUMN query_response_json LONGTEXT NULL AFTER query_request_json;

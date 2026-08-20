ALTER TABLE ai_video_task_items
    ADD COLUMN product_function_analysis_json LONGTEXT NULL AFTER video_prompt_text;

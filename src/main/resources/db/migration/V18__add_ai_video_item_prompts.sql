ALTER TABLE ai_video_task_items
    ADD COLUMN video_prompt_text LONGTEXT NULL AFTER error_message;

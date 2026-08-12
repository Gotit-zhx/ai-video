ALTER TABLE video_script_shots
    ADD COLUMN motion_description TEXT NULL AFTER scene_description,
    ADD COLUMN motion_intensity VARCHAR(20) NULL AFTER motion_description;

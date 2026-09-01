ALTER TABLE video_scripts
    ADD COLUMN extract_audio BOOLEAN NOT NULL DEFAULT FALSE AFTER requirements;

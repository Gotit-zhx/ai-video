ALTER TABLE video_scripts
    DROP INDEX uk_video_scripts_active_owner,
    DROP COLUMN active_owner_key;

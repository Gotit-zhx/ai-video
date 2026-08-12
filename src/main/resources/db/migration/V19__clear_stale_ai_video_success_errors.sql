UPDATE ai_video_task_items item
SET item.error_message = NULL
WHERE item.status = 'IMAGE_READY'
  AND EXISTS (
      SELECT 1 FROM ai_video_task_assets asset
      WHERE asset.item_id = item.id AND asset.asset_type = 'MODEL_IMAGE'
  )
  AND EXISTS (
      SELECT 1 FROM ai_video_task_assets asset
      WHERE asset.item_id = item.id AND asset.asset_type = 'STORYBOARD_GRID'
  );

UPDATE ai_video_tasks task
SET task.error_message = NULL
WHERE task.status = 'IMAGE_READY'
  AND NOT EXISTS (
      SELECT 1
      FROM ai_video_task_items item
      WHERE item.task_id = task.id
        AND (
          item.status <> 'IMAGE_READY'
          OR NOT EXISTS (
              SELECT 1 FROM ai_video_task_assets asset
              WHERE asset.item_id = item.id AND asset.asset_type = 'MODEL_IMAGE'
          )
          OR NOT EXISTS (
              SELECT 1 FROM ai_video_task_assets asset
              WHERE asset.item_id = item.id AND asset.asset_type = 'STORYBOARD_GRID'
          )
        )
  );

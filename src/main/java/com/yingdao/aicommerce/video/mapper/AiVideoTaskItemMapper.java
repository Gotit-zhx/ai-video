package com.yingdao.aicommerce.video.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
@Mapper public interface AiVideoTaskItemMapper extends BaseMapper<AiVideoTaskItem> {
    @Update("""
        UPDATE ai_video_task_items SET image_progress_json = JSON_SET(
          COALESCE(image_progress_json, JSON_OBJECT()), CONCAT('$.', #{assetType}),
          JSON_OBJECT(
            'stage', #{stage},
            'trackedSince', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(image_progress_json, CONCAT('$.', #{assetType}, '.trackedSince'))), DATE_FORMAT(UTC_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%fZ')),
            'updatedAt', DATE_FORMAT(UTC_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%fZ'),
            'generationAttempts', COALESCE(JSON_EXTRACT(image_progress_json, CONCAT('$.', #{assetType}, '.generationAttempts')), 0) + IF(#{stage} = 'GENERATING', 1, 0),
            'analysisAttempts', COALESCE(JSON_EXTRACT(image_progress_json, CONCAT('$.', #{assetType}, '.analysisAttempts')), 0) + IF(#{stage} = 'ANALYZING', 1, 0),
            'validationAttempts', COALESCE(JSON_EXTRACT(image_progress_json, CONCAT('$.', #{assetType}, '.validationAttempts')), 0) + IF(#{stage} = 'VALIDATING', 1, 0),
            'downloadAttempts', COALESCE(JSON_EXTRACT(image_progress_json, CONCAT('$.', #{assetType}, '.downloadAttempts')), 0) + IF(#{stage} = 'DOWNLOADING', 1, 0)
          )) WHERE id = #{itemId}
        """)
    int recordImageStep(@Param("itemId") String itemId, @Param("assetType") String assetType,
                        @Param("stage") String stage);
    @Update("UPDATE ai_video_task_items SET image_progress_json = JSON_REMOVE(COALESCE(image_progress_json, JSON_OBJECT()), CONCAT('$.', #{assetType})) WHERE id = #{itemId}")
    int resetImageStep(@Param("itemId") String itemId, @Param("assetType") String assetType);
}

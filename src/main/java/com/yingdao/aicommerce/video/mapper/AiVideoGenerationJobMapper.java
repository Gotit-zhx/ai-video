package com.yingdao.aicommerce.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AiVideoGenerationJobMapper extends BaseMapper<AiVideoGenerationJob> {
    @Update("""
            UPDATE ai_video_generation_jobs
               SET next_poll_at = #{reservedUntil}, updated_at = CURRENT_TIMESTAMP(6)
             WHERE id = #{id}
               AND status IN ('QUEUED', 'RUNNING', 'REVIEWING')
               AND next_poll_at <= #{now}
            """)
    int claimPoll(@Param("id") String id, @Param("now") Instant now,
                  @Param("reservedUntil") Instant reservedUntil);
}

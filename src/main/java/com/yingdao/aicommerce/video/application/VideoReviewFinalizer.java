package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publish a reviewed candidate and its terminal job/item state atomically. */
@Service
public class VideoReviewFinalizer {
    private final AiVideoGenerationJobMapper jobs;
    private final AiVideoTaskAssetMapper assets;
    private final AiVideoTaskItemMapper items;

    public VideoReviewFinalizer(AiVideoGenerationJobMapper jobs, AiVideoTaskAssetMapper assets,
                               AiVideoTaskItemMapper items) {
        this.jobs = jobs;
        this.assets = assets;
        this.items = items;
    }

    @Transactional
    public boolean finish(AiVideoGenerationJob job, AiVideoTaskItem item,
                          AiVideoTaskAsset candidate, boolean passed) {
        if (jobs.update(job, new LambdaUpdateWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getId, job.getId())
                .eq(AiVideoGenerationJob::getItemId, item.getId())
                .eq(AiVideoGenerationJob::getOrganizationId, job.getOrganizationId())
                .eq(AiVideoGenerationJob::getStatus, "REVIEWING")) != 1) return false;
        if (passed) {
            // Retain the bytes and metadata of any preceding video; only the approved
            // candidate gets the VIDEO_RESULT slot recognized by completion checks.
            for (AiVideoTaskAsset previous : assets.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                    .eq(AiVideoTaskAsset::getItemId, item.getId())
                    .eq(AiVideoTaskAsset::getOrganizationId, job.getOrganizationId())
                    .eq(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT"))) {
                int archived = assets.update(null, new LambdaUpdateWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getId, previous.getId())
                        .eq(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT")
                        .set(AiVideoTaskAsset::getAssetType, "VIDEO_PREVIOUS_RESULT")
                        .set(AiVideoTaskAsset::getShotSequence, null));
                if (archived != 1) throw new IllegalStateException("原视频状态发生变化，未发布审核候选");
            }
            int changed = assets.update(null, new LambdaUpdateWrapper<AiVideoTaskAsset>()
                    .eq(AiVideoTaskAsset::getId, candidate.getId())
                    .eq(AiVideoTaskAsset::getItemId, item.getId())
                    .eq(AiVideoTaskAsset::getOrganizationId, job.getOrganizationId())
                    .eq(AiVideoTaskAsset::getTaskId, job.getTaskId())
                    .eq(AiVideoTaskAsset::getAssetType, "VIDEO_REVIEW_CANDIDATE")
                    .set(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT")
                    .set(AiVideoTaskAsset::getShotSequence, 1));
            if (changed != 1) throw new IllegalStateException("审核候选状态发生变化，未发布视频");
            item.videoComplete();
        } else item.videoFail(job.getErrorMessage());
        if (items.updateById(item) != 1) throw new IllegalStateException("商品状态无法保存，未提交视频审核结果");
        return true;
    }
}

package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@TableName("video_script_shots")
public class VideoScriptShot {

    @TableId
    private String id;
    private String organizationId;
    private String scriptId;
    private int sequenceNo;
    private String stage;
    private String title;
    private BigDecimal startSecond;
    private BigDecimal endSecond;
    private BigDecimal durationSeconds;
    private String sceneDescription;
    private String motionDescription;
    private String motionIntensity;
    private String narration;
    private String shotSize;
    private String cameraMovement;
    private String transitionDescription;
    private String emotion;
    private String imagePromptJson;
    private Instant createdAt;
    private Instant updatedAt;

    protected VideoScriptShot() {
    }

    public VideoScriptShot(
            String organizationId,
            String scriptId,
            int sequenceNo,
            String stage,
            String title,
            BigDecimal startSecond,
            BigDecimal endSecond,
            BigDecimal durationSeconds,
            String sceneDescription,
            String motionDescription,
            String motionIntensity,
            String narration,
            String shotSize,
            String cameraMovement,
            String transitionDescription,
            String emotion,
            String imagePromptJson
    ) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.scriptId = scriptId;
        this.sequenceNo = sequenceNo;
        this.stage = stage;
        this.title = title;
        this.startSecond = startSecond;
        this.endSecond = endSecond;
        this.durationSeconds = durationSeconds;
        this.sceneDescription = sceneDescription;
        this.motionDescription = motionDescription;
        this.motionIntensity = motionIntensity;
        this.narration = narration;
        this.shotSize = shotSize;
        this.cameraMovement = cameraMovement;
        this.transitionDescription = transitionDescription;
        this.emotion = emotion;
        this.imagePromptJson = imagePromptJson;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateContent(
            String stage,
            String title,
            String sceneDescription,
            String motionDescription,
            String motionIntensity,
            String narration,
            String shotSize,
            String cameraMovement,
            String transitionDescription,
            String emotion
    ) {
        this.stage = stage;
        this.title = title;
        this.sceneDescription = sceneDescription;
        this.motionDescription = motionDescription;
        this.motionIntensity = motionIntensity;
        this.narration = narration;
        this.shotSize = shotSize;
        this.cameraMovement = cameraMovement;
        this.transitionDescription = transitionDescription;
        this.emotion = emotion;
        this.updatedAt = Instant.now();
    }

    public void retime(int sequence, BigDecimal start, BigDecimal end, String promptJson) {
        this.sequenceNo = sequence;
        this.startSecond = start;
        this.endSecond = end;
        this.durationSeconds = end.subtract(start);
        this.imagePromptJson = promptJson;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getScriptId() { return scriptId; }
    public int getSequenceNo() { return sequenceNo; }
    public String getStage() { return stage; }
    public String getTitle() { return title; }
    public BigDecimal getStartSecond() { return startSecond; }
    public BigDecimal getEndSecond() { return endSecond; }
    public BigDecimal getDurationSeconds() { return durationSeconds; }
    public String getSceneDescription() { return sceneDescription; }
    public String getMotionDescription() { return motionDescription; }
    public String getMotionIntensity() { return motionIntensity; }
    public String getNarration() { return narration; }
    public String getShotSize() { return shotSize; }
    public String getCameraMovement() { return cameraMovement; }
    public String getTransitionDescription() { return transitionDescription; }
    public String getEmotion() { return emotion; }
    public String getImagePromptJson() { return imagePromptJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

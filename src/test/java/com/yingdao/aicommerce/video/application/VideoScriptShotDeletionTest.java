package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class VideoScriptShotDeletionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final VideoScriptMapper scripts=mock(VideoScriptMapper.class);
    private final VideoScriptShotMapper shots=mock(VideoScriptShotMapper.class);
    private final VideoScriptService service=new VideoScriptService("target/test-delete",scripts,shots,
            mock(VideoScriptAnalysisProcessor.class),mock(VideoFrameExtractor.class),json);
    private final PlatformPrincipal user=new PlatformPrincipal("u","org","org","user","User","x",true,
            List.of("OPERATOR"),List.of("video:script:manage"));
    private VideoScript script() {
        VideoScript s=new VideoScript("s","org","test","MANUAL","MODEL_WEAR",null,null,null,null,"中国",null,"someone");
        s.complete("summary","tone","{}");when(scripts.selectOne(any())).thenReturn(s);return s;
    }
    private List<VideoScriptShot> fixtures() {
        List<VideoScriptShot> values=new ArrayList<>();BigDecimal start=BigDecimal.ZERO;
        for(int i=1;i<=9;i++) {
            BigDecimal end=start.add(BigDecimal.valueOf(i));
            values.add(new VideoScriptShot("org","s",i,"CONTENT","scene"+i,start,end,BigDecimal.valueOf(i),
                    "visual"+i,"motion"+i,"LOW","narration"+i,"medium","static","cut","calm","{}"));start=end;
        }
        when(shots.selectList(any())).thenAnswer(x->new ArrayList<>(values));
        doAnswer(x->{values.removeIf(v->v.getId().equals(x.getArgument(0)));return 1;}).when(shots).deleteById(any(java.io.Serializable.class));
        return values;
    }
    @Test void deletionKeepsDurationOrderSourceFramesAndFrozenGenerationContent() throws Exception {
        VideoScript script=script();List<VideoScriptShot> values=fixtures();String removed=values.get(3).getId();
        var result=service.deleteShot("s",removed,user);
        assertThat(result.shots()).hasSize(8);assertThat(values).hasSize(8);
        assertThat(result.shots()).extracting(VideoScriptShot::getSequenceNo).containsExactly(1,2,3,4,5,6,7,8);
        assertThat(result.shots().getLast().getEndSecond()).isEqualByComparingTo("45");
        assertThat(result.shots().getFirst().getDurationSeconds()).isEqualByComparingTo("1.098");
        assertThat(result.shots().stream().map(VideoScriptShot::getDurationSeconds).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("45");
        assertThat(script.getScriptJson()).doesNotContain("scene4","visual4","narration4");
        Map<?,?> prompt=json.readValue(values.get(3).getImagePromptJson(),Map.class);
        assertThat(((Number)prompt.get("sourcePreviewSequence")).intValue()).isEqualTo(5);
        String frozen=StoryboardShotPlan.forGeneration(script.getScriptJson(),json);
        assertThat(frozen).doesNotContain("scene4","visual4","narration4");
        List<Map<String,Object>> plan=(List<Map<String,Object>>)json.readValue(frozen,Map.class).get("shots");
        assertThat(plan).hasSize(9);
        assertThat(plan.stream().filter(v->!String.valueOf(v.get("narration")).isBlank())).hasSize(8);
        assertThat(new BigDecimal(String.valueOf(plan.getLast().get("endSecond")))).isEqualByComparingTo("45");
        while(values.size()>1) service.deleteShot("s",values.getFirst().getId(),user);
        assertThat(values.getFirst().getEndSecond()).isEqualByComparingTo("45");
        assertThat(((Number)json.readValue(values.getFirst().getImagePromptJson(),Map.class).get("sourcePreviewSequence")).intValue()).isEqualTo(9);
        assertThatThrownBy(()->service.deleteShot("s",values.getFirst().getId(),user)).hasMessageContaining("至少保留");
    }
    @Test void rejectsMissingShotAndUnfinishedScriptWithoutDeleting() {
        VideoScript script=script();fixtures();
        assertThatThrownBy(()->service.deleteShot("s","missing",user)).hasMessageContaining("分镜不存在");
        when(scripts.selectOne(any())).thenReturn(null);
        assertThatThrownBy(()->service.deleteShot("other","x",user)).hasMessageContaining("视频脚本不存在");
        verify(shots,never()).deleteById(any(java.io.Serializable.class));
    }
}

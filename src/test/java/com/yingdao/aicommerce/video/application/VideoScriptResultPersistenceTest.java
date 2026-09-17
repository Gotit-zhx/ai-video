package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.domain.VideoScriptShot;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VideoScriptResultPersistenceTest {
    @Test void lockRetryPersistsExistingResultWithoutCallingModelAndWithoutChangingSharedTemplate() {
        var scripts=mock(VideoScriptMapper.class); var shots=mock(VideoScriptShotMapper.class);
        var models=mock(ModelConfigService.class); var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition def=call.getArgument(0);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,def.getIsolationLevel());
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,def.getPropagationBehavior());
            return new SimpleTransactionStatus();
        });
        var shared=new TransactionTemplate(manager);
        var processor=new VideoScriptAnalysisProcessor(scripts,shots,models,mock(VideoFrameExtractor.class),
                mock(VideoCameraMotionAnalyzer.class),mock(DayanAudioTranscriptionService.class),new ObjectMapper(),shared,"http://localhost:1","qa");
        var script=new VideoScript("qa-script","org","qa","VIDEO_ANALYSIS","MODEL_WEAR","source.mp4","/tmp/source.mp4","video/mp4",1L,null,null,"user");
        var shot=new VideoScriptAnalysisResult.Shot(1,"HOOK","test",BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.ONE,"scene","move","微幅","","medium","fixed","none","calm",Map.of());
        var result=new VideoScriptAnalysisResult("title","summary","tone",BigDecimal.ONE,List.of(shot));
        when(shots.insert(any(VideoScriptShot.class))).thenThrow(new CannotAcquireLockException("deadlock")).thenReturn(1);
        processor.persistResult(script,result);
        verify(manager).rollback(any()); verify(manager).commit(any());
        verify(shots,times(2)).insert(any(VideoScriptShot.class)); verify(scripts).updateById(script);
        verifyNoInteractions(models);
        assertEquals("READY",script.getStatus());
        assertEquals(TransactionDefinition.ISOLATION_DEFAULT,shared.getIsolationLevel());
    }
}

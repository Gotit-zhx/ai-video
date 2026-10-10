package com.yingdao.aicommerce.video.application;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class VideoDirectDownloadLifecycleTest {
 @TempDir Path root;
 @Test void temporaryVideoIsDeletedOnlyAfterApprovedLinkIsCommitted() throws Exception {
  for(boolean committed:List.of(false,true)){
   var tasks=mock(AiVideoTaskMapper.class);var items=mock(AiVideoTaskItemMapper.class);var assets=mock(AiVideoTaskAssetMapper.class);
   var task=mock(AiVideoTask.class);when(task.getId()).thenReturn("task");when(tasks.selectById("task")).thenReturn(task);
   var item=mock(AiVideoTaskItem.class);when(items.selectById("item")).thenReturn(item);
   var job=mock(AiVideoGenerationJob.class);when(job.getId()).thenReturn("job");when(job.getTaskId()).thenReturn("task");when(job.getItemId()).thenReturn("item");when(job.getOrganizationId()).thenReturn("org");when(job.getUsageJson()).thenReturn("{}");
   Path video=Files.write(root.resolve("video-"+committed+".mp4"),new byte[]{1,2,3});
   var candidate=new AiVideoTaskAsset("org","task","item","VIDEO_REVIEW_CANDIDATE",null,video.toString(),"video/mp4",3,"{\"jobId\":\"job\",\"directDownloadUrl\":\"https://provider.example/video.mp4?token=opaque\"}","model");
   when(assets.selectList(any())).thenReturn(List.of(candidate));
   ObjectProvider<OssStorageService> oss=mock(ObjectProvider.class);
   var service=new AiVideoGenerationService(tasks,items,assets,null,null,null,null,null,null,oss,new ObjectMapper(),Runnable::run,1);
   var audit=mock(VideoFirstFrameAuditService.class);when(audit.audit(task,item,video.toString(),"job")).thenReturn(new VideoFirstFrameAuditService.AuditResult("SINGLE_SCENE",1,"single scene",null,0));
   var finalizer=mock(VideoReviewFinalizer.class);when(finalizer.finish(job,item,candidate,true)).thenReturn(committed);
   service.setFirstFrameAudit(audit);service.setReviewFinalizer(finalizer);
   ReflectionTestUtils.invokeMethod(service,"reviewSavedVideo",job);
   verify(finalizer).finish(job,item,candidate,true);
   assertThat(candidate.getFilePath()).isEqualTo("provider-video:job");
   assertThat(Files.exists(video)).isEqualTo(!committed);
  }
 }
}

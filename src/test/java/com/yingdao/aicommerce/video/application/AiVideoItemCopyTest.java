package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AiVideoItemCopyTest {
    @TempDir Path root;
    final AiVideoTaskMapper tasks = mock(AiVideoTaskMapper.class);
    final AiVideoTaskItemMapper items = mock(AiVideoTaskItemMapper.class);
    final AiVideoTaskAssetMapper assets = mock(AiVideoTaskAssetMapper.class);
    final PlatformPrincipal principal = new PlatformPrincipal("user", "org", "company", "13800000000",
            "operator", "encoded", true, List.of("OPERATOR"), List.of("video:task:manage"));
    @SuppressWarnings("unchecked")
    AiVideoTaskService service() {
        return new AiVideoTaskService(tasks, items, assets, null, null, null, null, new ObjectMapper(),
                null, null, null, mock(ObjectProvider.class), root.toString());
    }
    AiVideoTask task() {
        AiVideoTask task = new AiVideoTask("org", "test", "script", 1, "script", "{}", "MY",
                "CAST_PRODUCT", "SCRIPT_ORIGINAL", "product", "scene", "action", false,
                "9:16", "720p", false, true, "{}", "{}", "user");
        when(tasks.selectOne(any())).thenReturn(task);
        return task;
    }
    @Test void copiesIndependentInputsAndInstructionsWithoutVideosOrGenerationState() throws Exception {
        AiVideoTask task = task();
        Path file = Files.write(root.resolve("source.png"), new byte[]{1,2,3});
        AiVideoTaskItem original = new AiVideoTaskItem("org",task.getId(),"group",null,"source.png",file.toString(),"image/png",3);
        original.updateVideoPrompt("use @图片1"); original.videoComplete();
        when(items.selectOne(any())).thenReturn(original);
        when(items.selectCount(any())).thenReturn(1L);
        AiVideoTaskAsset reference = AiVideoTaskAsset.videoReference("org", task.getId(), original.getId(), 1,
                file.toString(), "image/png",3,"APPEARANCE","replace product",true);
        AiVideoTaskAsset video = new AiVideoTaskAsset("org",task.getId(), original.getId(),"VIDEO_RESULT",1,
                "/not-to-be-read.mp4","video/mp4",10,"","");
        when(assets.selectList(any())).thenReturn(List.of(reference,video));
        List<AiVideoTaskItem> copies = service().copyItem(task.getId(),original.getId(),2,principal);
        assertEquals(2,copies.size());
        assertNotEquals(copies.get(0).getId(),copies.get(1).getId());
        assertNotEquals(copies.get(0).getSku(), copies.get(1).getSku());
        assertEquals(3,task.getTotalItems()); assertEquals("READY",task.getStatus());
        ArgumentCaptor<AiVideoTaskAsset> saved = ArgumentCaptor.forClass(AiVideoTaskAsset.class);
        verify(assets,times(2)).insert(saved.capture());
        Files.delete(file);
        for(int i=0;i<2;i++) {
            var copy=copies.get(i); var asset=saved.getAllValues().get(i);
            assertEquals("READY",copy.getStatus()); assertEquals("use @图片1",copy.getVideoPromptText());
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(Path.of(copy.getSourceFilePath())));
            assertEquals(copy.getSourceFilePath(),asset.getFilePath());
            assertEquals(copy.getId(),asset.getItemId()); assertTrue(asset.isPrimaryReference());
            assertEquals("replace product",asset.getReferenceInstruction());
        }
        assertNotEquals(copies.get(0).getSourceFilePath(),copies.get(1).getSourceFilePath());
    }
    @Test void rejectsCapacityAndForeignItemsBeforeWriting() {
        var task=task(); var original=new AiVideoTaskItem("org",task.getId(),"group",null,"a.png","missing","image/png",1);
        when(items.selectOne(any())).thenReturn(original);when(items.selectCount(any())).thenReturn(29L);
        assertThrows(BusinessException.class,()->service().copyItem(task.getId(),original.getId(),2,principal));
        verify(items,never()).insert(any(AiVideoTaskItem.class)); verifyNoInteractions(assets);
        when(items.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class,()->service().copyItem(task.getId(),"foreign",1,principal));
    }
    @Test void rejectsInvalidCountAndUnauthorizedTask() {
        for(int n:new int[]{0,-1,30,Integer.MAX_VALUE}) assertThrows(BusinessException.class,()->service().copyItem("x","y",n,principal));
        verifyNoInteractions(tasks,items,assets);
        assertThrows(BusinessException.class,()->service().copyItem("foreign","y",1,principal));
        verifyNoInteractions(items,assets);
    }
    @Test void cleansCopiedFilesOnMidCopyFailureAndLeavesSourceIntact() throws Exception {
        var task=task(); Path file=Files.write(root.resolve("source.png"),new byte[]{1});
        var original=new AiVideoTaskItem("org",task.getId(),"group",null,"a.png",file.toString(),"image/png",1);
        when(items.selectOne(any())).thenReturn(original);when(items.selectCount(any())).thenReturn(1L);
        when(assets.selectList(any())).thenReturn(List.of(AiVideoTaskAsset.videoReference("org",task.getId(),original.getId(),1,
                root.resolve("missing.png").toString(),"image/png",1,"APPEARANCE","",true)));
        assertThrows(BusinessException.class,()->service().copyItem(task.getId(),original.getId(),1,principal));
        assertTrue(Files.exists(file));
        try(var paths=Files.walk(root)){ assertEquals(1,paths.filter(Files::isRegularFile).count()); }
    }
}

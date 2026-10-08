package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiVideoLocalAssetFallbackTest {
    @TempDir Path root;
    private final AiVideoTaskMapper tasks = mock(AiVideoTaskMapper.class);
    private final AiVideoTaskAssetMapper assets = mock(AiVideoTaskAssetMapper.class);
    private final OssStorageService oss = mock(OssStorageService.class);
    private final AiVideoTask task = mock(AiVideoTask.class);
    private final String location = "oss://test-hoyee/heyi/generated/image.png";
    private final PlatformPrincipal principal = new PlatformPrincipal(
            "user", "org", "org", "13800000000", "测试", "encoded", true,
            List.of("ADMIN"), List.of());
    private AiVideoTaskService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, AiVideoTask.class);
        TableInfoHelper.initTableInfo(assistant, AiVideoTaskAsset.class);
        ObjectProvider<OssStorageService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oss);
        service = new AiVideoTaskService(tasks, null, assets, null, null, null,
                null, new ObjectMapper(), null, null, null, provider, root.toString());
        when(tasks.selectOne(any())).thenReturn(task);
        when(task.getId()).thenReturn("task");
        when(task.getOrganizationId()).thenReturn("org");
        when(oss.isOssLocation(location)).thenReturn(true);
    }

    @Test
    void exactCachedBytesRemainAvailableWithoutAStorageRequest() throws Exception {
        byte[] original = {1, 2, 3, 4};
        Path cached = cache(original);
        when(assets.selectOne(any())).thenReturn(asset("image/png", original.length));
        var result = service.asset("task", "asset", principal);
        assertEquals(cached, result.path());
        assertNull(result.location());
        assertArrayEquals(original, Files.readAllBytes(result.path()));
        verify(oss, never()).exists(any());
        verify(oss, never()).signedUrl(any());
    }

    @Test
    void truncatedCacheUsesTheOriginalRemoteObject() throws Exception {
        cache(new byte[]{1});
        when(assets.selectOne(any())).thenReturn(asset("image/png", 4));
        when(oss.exists(location)).thenReturn(true);
        var result = service.asset("task", "asset", principal);
        assertNull(result.path());
        assertEquals(location, result.location());
        verify(oss).exists(location);
    }

    @Test
    void missingCachePreservesRemoteMissingObjectError() {
        when(assets.selectOne(any())).thenReturn(asset("image/png", 4));
        assertThrows(BusinessException.class, () -> service.asset("task", "asset", principal));
        verify(oss).exists(location);
    }

    @Test
    void cacheDoesNotBypassTaskAuthorizationOrAssetOwnership() throws Exception {
        cache(new byte[]{1, 2, 3, 4});
        when(tasks.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.asset("task", "asset", principal));
        verifyNoInteractions(assets, oss);
        when(tasks.selectOne(any())).thenReturn(task);
        assertThrows(BusinessException.class, () -> service.asset("task", "missing", principal));
        verifyNoInteractions(oss);
    }

    @Test
    void generalReferencesUseTheSameAuthorizedCache() throws Exception {
        Path cached = cache(new byte[]{1, 2, 3, 4});
        when(task.isGeneral()).thenReturn(true);
        when(assets.selectOne(any())).thenReturn(asset("image/jpeg", 4));
        assertEquals(cached, service.generalAsset("task", "asset", principal).path());
        verify(oss, never()).exists(any());
    }

    @Test
    void imageCacheIsNotUsedForRemoteVideoFiles() throws Exception {
        cache(new byte[]{1, 2, 3, 4});
        when(assets.selectOne(any())).thenReturn(asset("video/mp4", 4));
        when(oss.exists(location)).thenReturn(true);
        assertEquals(location, service.asset("task", "asset", principal).location());
        verify(oss).exists(location);
    }

    private AiVideoTaskAsset asset(String type, long size) {
        return new AiVideoTaskAsset("org", "task", "item", "MODEL_IMAGE", 1,
                location, type, size, null, "model");
    }

    private Path cache(byte[] bytes) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(location.getBytes(StandardCharsets.UTF_8)));
        Path directory = Files.createDirectories(root.resolve(".asset-cache"));
        return Files.write(directory.resolve(key + ".bin"), bytes);
    }
}

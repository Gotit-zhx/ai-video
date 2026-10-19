package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.storage.OssStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommerceImageOssArchiveTest {
    @TempDir Path root;
    final OssStorageService oss=mock(OssStorageService.class);
    final Map<String,byte[]> objects=new HashMap<>();
    CommerceImageArchive archive;
    @BeforeEach void setup()throws Exception {
        when(oss.commerceImageLocation(anyString())).thenAnswer(call->"oss://private/heyi/commerce-images/"+call.getArgument(0));
        when(oss.exists(anyString())).thenAnswer(call->objects.containsKey(call.getArgument(0)));
        when(oss.storeCommerceImage(anyString(),any(),anyString())).thenAnswer(call->{
            String location=oss.commerceImageLocation(call.getArgument(0));byte[] bytes=call.getArgument(1);
            objects.put(location,bytes.clone());return new OssStorageService.StoredObject(location,bytes.length);
        });
        when(oss.readAllBytes(anyString(),anyInt())).thenAnswer(call->objects.get(call.getArgument(0)));
        archive=new CommerceImageArchive(root.toString(),oss);
    }
    byte[] image()throws Exception{return new CommerceImageArchiveTest().png();}
    @Test void newImagesGoOnlyToOssAndRetainTenantScopedContentKeys()throws Exception {
        byte[] bytes=image();var saved=archive.store("org",bytes);
        assertEquals(saved,archive.store("org",bytes));
        assertArrayEquals(bytes,Base64.getDecoder().decode(archive.read("org",saved.storageKey()).dataBase64()));
        assertFalse(Files.exists(root.resolve(saved.storageKey())));
        verify(oss,times(1)).storeCommerceImage(anyString(),any(),eq("image/png"));
        clearInvocations(oss);
        assertThrows(IllegalArgumentException.class,()->archive.read("other",saved.storageKey()));
        assertThrows(IllegalArgumentException.class,()->archive.read("org","../secret"));
        verifyNoInteractions(oss);
    }
    @Test void existingReferencesMigrateOnReadWithoutChangingTheirKey()throws Exception {
        var local=new CommerceImageArchive(root.toString());byte[] bytes=image();var saved=local.store("org",bytes);
        assertArrayEquals(bytes,Base64.getDecoder().decode(archive.read("org",saved.storageKey()).dataBase64()));
        assertFalse(Files.exists(root.resolve(saved.storageKey())));
        assertTrue(objects.containsKey(oss.commerceImageLocation(saved.storageKey())));
    }
    @Test void outageNeverWritesNewLocalImagesOrDeletesExistingOnes()throws Exception {
        var saved=new CommerceImageArchive(root.toString()).store("old",image());
        doThrow(new IllegalStateException("offline")).when(oss).storeCommerceImage(anyString(),any(),anyString());
        assertThrows(IllegalStateException.class,()->archive.store("new",image()));
        assertThrows(IllegalStateException.class,()->archive.migrateLocal(25));
        assertTrue(Files.exists(root.resolve(saved.storageKey())));
        try(var paths=Files.walk(root)){assertEquals(1,paths.filter(Files::isRegularFile).count());}
    }
    @Test void verificationFailurePreservesLocalBytesAndNeverAcceptsCorruptRemoteImages()throws Exception {
        byte[] bytes=image();var saved=new CommerceImageArchive(root.toString()).store("org",bytes);
        byte[] corrupt=bytes.clone();corrupt[corrupt.length-1]^=1;
        when(oss.readAllBytes(anyString(),anyInt())).thenReturn(corrupt);
        assertThrows(IllegalStateException.class,()->archive.migrateLocal(25));
        assertArrayEquals(bytes,Files.readAllBytes(root.resolve(saved.storageKey())));
        assertThrows(IllegalStateException.class,()->archive.read("org",saved.storageKey()));
    }
    @Test void migrationIsBoundedAndDeletesOnlyVerifiedImageFiles()throws Exception {
        var local=new CommerceImageArchive(root.toString());
        var first=local.store("first",image());var second=local.store("second",image());
        Path unrelated=root.resolve("keep.txt");Files.writeString(unrelated,"metadata");
        assertEquals(1,archive.migrateLocal(1));
        assertEquals(1,archive.migrateLocal(25));
        assertEquals(0,archive.migrateLocal(25));
        assertFalse(Files.exists(root.resolve(first.storageKey())));assertFalse(Files.exists(root.resolve(second.storageKey())));
        assertEquals("metadata",Files.readString(unrelated));assertEquals(2,objects.size());
    }
    @Test void migrationNeverTraversesTenantSymlinks()throws Exception {
        Path outside=Files.createTempDirectory("outside-commerce");
        try {
            Files.write(outside.resolve("b".repeat(64)),image());
            Files.createSymbolicLink(root.resolve("a".repeat(64)),outside);
            assertEquals(0,archive.migrateLocal(25));verifyNoInteractions(oss);
            assertTrue(Files.exists(outside.resolve("b".repeat(64))));
        } finally {Files.deleteIfExists(outside.resolve("b".repeat(64)));Files.deleteIfExists(outside);}
    }
}

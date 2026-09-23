package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeFileServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final KnowledgeService knowledge=mock(KnowledgeService.class);
    private final OssStorageService oss=mock(OssStorageService.class);
    private final KnowledgeFilePreviewService previews=mock(KnowledgeFilePreviewService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<OssStorageService> provider=mock(ObjectProvider.class);
    private final PlatformPrincipal user=new PlatformPrincipal("user-a","org-a","a","user","User","",true,List.of(),List.of());
    private final KnowledgeFileService service=new KnowledgeFileService(db,knowledge,new ObjectMapper(),provider,"dayan-private",previews);
    private void access(){when(provider.getIfAvailable()).thenReturn(oss);when(knowledge.readAccess(user)).thenReturn(Map.of("id","user-a","display_name","Current User","username","current"));when(knowledge.requireWrite(user)).thenReturn(Map.of("id","user-a","display_name","Current User","username","current"));}
    @Test void disabledMembershipStopsBeforeStorageOrDatabaseWrites() {
        when(knowledge.readAccess(user)).thenThrow(new BusinessException(HttpStatus.FORBIDDEN,"Disabled"));
        assertThrows(BusinessException.class,()->service.upload(new MockMultipartFile("file","a.txt","text/plain","text".getBytes()),UUID.randomUUID().toString(),null,"system",user));
        verifyNoInteractions(oss,db,provider);
    }
    @Test void noOssConfigurationHasNoLocalFallbackAndNoSuccessWrites() {
        access();when(provider.getIfAvailable()).thenReturn(null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,()->service.upload(new MockMultipartFile("file","a.txt","text/plain","text".getBytes()),UUID.randomUUID().toString(),null,"system",user)).getStatus());
        verifyNoInteractions(db,oss);
    }
    @Test void rejectsUnsupportedUnsafeAndOversizedUploadsBeforeOss() {
        access();for(String name:List.of("page.html","icon.svg","run.exe","bad\nname.txt"))assertThrows(BusinessException.class,()->service.upload(new MockMultipartFile("file",name,"text/plain","payload".getBytes()),UUID.randomUUID().toString(),null,"system",user));
        var large=mock(org.springframework.web.multipart.MultipartFile.class);when(large.getSize()).thenReturn((long)KnowledgeFileService.MAX_BYTES+1);when(large.isEmpty()).thenReturn(false);
        assertThrows(BusinessException.class,()->service.upload(large,UUID.randomUUID().toString(),null,"system",user));verifyNoInteractions(db,oss);
    }
    @Test void magicValidationRejectsDisguisedDocumentsAndImages() {
        for(String extension:List.of("pdf","doc","xls","docx","xlsx","ppt","pptx","png","jpg","jpeg","webp","mp4"))assertThrows(BusinessException.class,()->KnowledgeFileService.validateMagic("<html>not a document</html>".getBytes(),extension));
        assertDoesNotThrow(()->KnowledgeFileService.validateMagic("%PDF-1.7".getBytes(),"pdf"));
    }
    @Test void powerpointUsesExistingOleAndZipSignaturesWithCaseInsensitiveExtensions() {
        assertEquals("ppt",KnowledgeFileService.extension("培训.PPT"));assertEquals("pptx",KnowledgeFileService.extension("培训.PPTX"));
        byte[] ole={(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
        assertDoesNotThrow(()->KnowledgeFileService.validateMagic(ole,"ppt"));assertDoesNotThrow(()->KnowledgeFileService.validateMagic(new byte[]{'P','K',3,4},"pptx"));
        assertThrows(BusinessException.class,()->KnowledgeFileService.validateMagic(ole,"pptx"));
        assertThrows(BusinessException.class,()->KnowledgeFileService.extension("macros.pptm"));
    }
    @Test void crossTenantAttachmentDownloadDoesNotReachOss() {
        access();when(db.queryForList(anyString(),eq("org-a"),eq("foreign"))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.download("foreign",user)).getStatus());verifyNoInteractions(oss);
    }
    @Test void removedAttachmentCannotDownloadAndRemovalDoesNotDeleteOriginal() {
        access();var row=new HashMap<String,Object>();row.put("id","file-a");row.put("status","REMOVED");row.put("version",2);
        when(db.queryForList(anyString(),eq("org-a"),eq("file-a"))).thenReturn(List.of(row));
        assertThrows(BusinessException.class,()->service.download("file-a",user));service.remove("file-a",1,user);
        verifyNoInteractions(oss);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void ordinaryMembersCannotReadUploadAuditEvenIfTheyCanEdit() {
        access();when(knowledge.administrator(user)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.audit(null,"system",user)).getStatus());verifyNoInteractions(db);
    }
    @Test void downloadUsesOnlyStoredAuthorizedObjectAndForcesSafeHeaders() {
        access();var row=new HashMap<String,Object>();row.put("id","file-a");row.put("status","ACTIVE");row.put("oss_location","oss://dayan-private/heyi/knowledge/org-a/file.txt");row.put("original_name","知识库说明.txt");row.put("size_bytes",4L);
        when(db.queryForList(anyString(),eq("org-a"),eq("file-a"))).thenReturn(List.of(row));when(oss.isOssLocation(anyString())).thenReturn(true);
        when(oss.download(anyString())).thenReturn(new OssStorageService.Download(new ByteArrayResource("text".getBytes()),4,"text/html"));
        var response=new KnowledgeFileController(service).download("file-a",user);
        assertEquals("application/octet-stream",response.getHeaders().getContentType().toString());assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));assertTrue(response.getHeaders().getContentDisposition().isAttachment());assertEquals("no-store",response.getHeaders().getCacheControl());
    }
    @Test void imageAndPdfPreviewsUseExactSafeMimeAndPrivateInlineHeaders() {
        access();for(var kind:Map.of("png","image/png","jpg","image/jpeg","jpeg","image/jpeg","webp","image/webp","pdf","application/pdf").entrySet()) {
            var row=new HashMap<String,Object>();row.put("id","preview-a");row.put("status","ACTIVE");row.put("extension",kind.getKey());row.put("oss_location","oss://dayan-private/heyi/knowledge/org-a/original");row.put("original_name","真实文件."+kind.getKey());row.put("size_bytes",4L);
            when(db.queryForList(anyString(),eq("org-a"),eq("preview-a"))).thenReturn(List.of(row));when(oss.isOssLocation(anyString())).thenReturn(true);
            when(oss.download(anyString())).thenReturn(new OssStorageService.Download(new ByteArrayResource("data".getBytes()),4,"text/html"));
            var response=new KnowledgeFileController(service).preview("preview-a",user);
            assertEquals(kind.getValue(),response.getHeaders().getContentType().toString());assertTrue(response.getHeaders().getContentDisposition().isInline());
            assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));assertEquals("same-origin",response.getHeaders().getFirst("Cross-Origin-Resource-Policy"));
            assertTrue(response.getHeaders().getCacheControl().contains("no-store"));assertTrue(response.getHeaders().getCacheControl().contains("private"));
        }
    }
    @Test void previewRejectsOtherTenantsRemovedFilesDisabledMembersAndUnsupportedFormats() {
        access();when(db.queryForList(anyString(),eq("org-a"),eq("other"))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.preview("other",user)).getStatus());
        when(db.queryForList(anyString(),eq("org-a"),eq("removed"))).thenReturn(List.of(Map.of("status","REMOVED","extension","png")));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.preview("removed",user)).getStatus());
        for(String extension:List.of("html","svg","doc","txt")) {
            when(db.queryForList(anyString(),eq("org-a"),eq("unsupported"))).thenReturn(List.of(Map.of("status","ACTIVE","extension",extension)));
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(BusinessException.class,()->service.preview("unsupported",user)).getStatus());
        }
        when(knowledge.readAccess(user)).thenThrow(new BusinessException(HttpStatus.FORBIDDEN,"Disabled"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.preview("other",user)).getStatus());verifyNoInteractions(oss);
    }
    @Test void powerpointPreviewRechecksMembershipAfterConversionAndNeverServesOriginalAsPdf() {
        access();var row=Map.<String,Object>of("id","slides","status","ACTIVE","extension","pptx","oss_location","oss://dayan-private/slides.pptx","original_name","培训.pptx","sha256","a".repeat(64));
        when(db.queryForList(anyString(),eq("org-a"),eq("slides"))).thenReturn(List.of(row));
        when(previews.convert(anyString(),anyString(),anyString(),eq("pptx"))).thenReturn(new OssStorageService.Download(new ByteArrayResource("%PDF-1.7".getBytes()),8,"application/pdf"));
        var response=new KnowledgeFileController(service).preview("slides",user);assertEquals("application/pdf",response.getHeaders().getContentType().toString());assertEquals("培训.pptx.pdf",response.getHeaders().getContentDisposition().getFilename());
        when(knowledge.readAccess(user)).thenReturn(Map.of("id","user-a")).thenThrow(new BusinessException(HttpStatus.FORBIDDEN,"Disabled after conversion"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.preview("slides",user)).getStatus());verifyNoInteractions(oss);
    }
}

package com.yingdao.aicommerce.video.web;

import com.aliyun.oss.OSSException;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.storage.OssReferenceLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A time-limited object capability, never an unrestricted OSS proxy. */
@RestController
public class PublicOssReferenceController {
    private final OssReferenceLinkService links;
    private final ObjectProvider<OssStorageService> storage;

    public PublicOssReferenceController(OssReferenceLinkService links, ObjectProvider<OssStorageService> storage) {
        this.links = links;
        this.storage = storage;
    }

    @GetMapping("/api/public/v1/video-references/oss-object/{id}")
    public ResponseEntity<Resource> object(@PathVariable String id,
                                           @RequestParam long expires,
                                           @RequestParam String signature,
                                           HttpServletRequest request) {
        // Validate before decoding or touching OSS, including HEAD/Range requests.
        if (!links.isValid("oss-object", id, expires, signature)) return ResponseEntity.notFound().build();
        OssStorageService oss = storage.getIfAvailable();
        if (oss == null) return ResponseEntity.notFound().build();
        String location;
        try {
            location = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
            if (!oss.isOssLocation(location)) return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ignored) {
            return ResponseEntity.notFound().build();
        }
        OssStorageService.Download download;
        try {
            download = oss.download(location);
        } catch (OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) return ResponseEntity.notFound().build();
            throw exception;
        }
        MediaType media;
        try { media = MediaType.parseMediaType(download.contentType()); }
        catch (IllegalArgumentException ignored) { media = MediaType.APPLICATION_OCTET_STREAM; }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(media).contentLength(download.size());
        // Do not download the object just to answer a provider's HEAD probe.
        return "HEAD".equals(request.getMethod()) ? response.build() : response.body(download.resource());
    }
}

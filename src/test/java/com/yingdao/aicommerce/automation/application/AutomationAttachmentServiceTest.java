package com.yingdao.aicommerce.automation.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationAttachmentServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void storesGeneratedImageWithPreviewContentType() throws Exception {
        AutomationAttachmentService service = new AutomationAttachmentService(storageRoot.toString());
        byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};

        var stored = service.storeGenerated(
                image,
                "generated.png",
                "image/png",
                "org-1",
                "user-1"
        );
        var loaded = service.load(stored.id());

        assertThat(loaded.name()).isEqualTo("generated.png");
        assertThat(loaded.contentType()).isEqualTo("image/png");
        assertThat(Files.readAllBytes(loaded.path())).containsExactly(image);
    }
}

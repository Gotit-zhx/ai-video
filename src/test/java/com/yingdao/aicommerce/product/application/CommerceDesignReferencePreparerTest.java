package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignReferencePreparerTest {
    final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceDesignReferencePreparer service=new CommerceDesignReferencePreparer(downloader,archive,new ObjectMapper());
    final CommerceDesignDraft draft=new CommerceDesignDraft("d","org","c",1,"key","hash",1,"STOCK_MARKETING","brief","rights","[{\"imageId\":\"i\",\"originalUrl\":\"https://cdn.example/image.png\"}]","DRAFT","u",Instant.now());
    @Test void onlyDownloadsSelectedSnapshotImage(){byte[] bytes={1,2,3};when(downloader.download("https://cdn.example/image.png")).thenReturn(new MarketplaceImageDownloader.ImageBinary(bytes,"image/png",".png"));when(archive.store("org",bytes)).thenReturn(new CommerceImageArchive.Archived("storage","hash","image/png",3));var result=service.prepare(draft,List.of("i"));assertEquals("storage",result.getFirst().storageKey());verify(archive).store("org",bytes);}
    @Test void foreignImageRejectedBeforeAnyNetwork(){assertThrows(IllegalArgumentException.class,()->service.prepare(draft,List.of("foreign")));verifyNoInteractions(downloader,archive);}
}

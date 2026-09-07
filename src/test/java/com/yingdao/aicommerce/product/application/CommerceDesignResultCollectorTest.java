package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignResultCollectorTest {
    final CommerceDesignAttemptMapper attempts=mock(CommerceDesignAttemptMapper.class);
    final CommerceDesignOutputMapper outputs=mock(CommerceDesignOutputMapper.class);
    final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceDesignOutputLedger ledger=mock(CommerceDesignOutputLedger.class);
    final CommerceDesignResultCollector collector=new CommerceDesignResultCollector(attempts,outputs,downloader,archive,ledger,new ObjectMapper());
    void setup(String state){when(attempts.get("org","attempt")).thenReturn(new CommerceDesignAttempt("attempt","org","design",state,"[]","{\"result\":{\"url\":\"https://cdn.example/out.png\"}}",null,Instant.now(),Instant.now()));}
    @Test void downloadsSavedUrlThenArchivesWithoutApproval(){setup("RESULT_RECEIVED");byte[] bytes={1,2,3};when(downloader.download("https://cdn.example/out.png")).thenReturn(new MarketplaceImageDownloader.ImageBinary(bytes,"image/png",".png"));var stored=new CommerceImageArchive.Archived("key","hash","image/png",3);when(archive.store("org",bytes)).thenReturn(stored);collector.collect("org","attempt");verify(ledger).attach("org","attempt",stored);}
    @Test void failedDownloadRetainsOriginalAttemptForDownloadRetry(){setup("RESULT_RECEIVED");when(downloader.download(anyString())).thenThrow(new IllegalStateException("timeout"));assertThrows(IllegalStateException.class,()->collector.collect("org","attempt"));verifyNoInteractions(ledger,archive);verify(attempts,never()).transition(anyString(),anyString(),anyString(),anyString(),any(),any());}
    @Test void unknownGenerationOutcomeCannotTriggerDownload(){setup("UNKNOWN");assertThrows(IllegalStateException.class,()->collector.collect("org","attempt"));verifyNoInteractions(downloader,archive,ledger);}
    @Test void archivedOutputIsReadLocallyInsteadOfDownloadingAgain(){setup("OUTPUT_ARCHIVED");var output=new CommerceDesignOutput("id","org","design","attempt","key","hash","image/png",3,"PENDING_REVIEW",Instant.now());when(outputs.byAttempt("org","attempt")).thenReturn(output);assertSame(output,collector.collect("org","attempt"));verify(archive).read("org","key");verifyNoInteractions(downloader,ledger);}
}

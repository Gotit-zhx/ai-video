package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.mapper.CommerceDesignAttemptMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignAttemptLedgerTest {
    final CommerceDesignAttemptMapper mapper=mock(CommerceDesignAttemptMapper.class);
    final CommerceDesignAttemptLedger ledger=new CommerceDesignAttemptLedger(mapper,new ObjectMapper());
    @Test void referenceContentIsRequiredBeforePreparing(){assertThrows(IllegalArgumentException.class,()->ledger.prepare("org","design",List.of(new CommerceDesignAttemptLedger.ReferenceManifest("image","bad","key"))));verifyNoInteractions(mapper);}
    @Test void preparePinsReferenceManifestAndReplays(){var refs=List.of(new CommerceDesignAttemptLedger.ReferenceManifest("image","a".repeat(64),"org/assets/source.png"));var row=ledger.prepare("org","design",refs);assertEquals("PREPARED",row.status());when(mapper.byDesign("org","design")).thenReturn(row);assertSame(row,ledger.prepare("org","design",refs));verify(mapper,times(1)).insert(any());}
    @Test void onlyClaimWinnerMaySubmit(){when(mapper.transition("org","id","PREPARED","SUBMITTING",null,null)).thenReturn(1,0);assertTrue(ledger.claim("org","id"));assertFalse(ledger.claim("org","id"));}
    @Test void uncertaintyCannotResetToPrepared(){ledger.uncertain("org","id");verify(mapper).transition("org","id","SUBMITTING","UNKNOWN",null,"PROVIDER_OUTCOME_UNKNOWN");}
    @Test void pendingDownloadIsPersistedWithoutApproval(){var r=new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult("https://example.com/image.png","image/png","model","edit",""));when(mapper.transition(eq("org"),eq("id"),eq("SUBMITTING"),eq("RESULT_RECEIVED"),anyString(),isNull())).thenReturn(1);ledger.received("org","id",r);verify(mapper).transition(eq("org"),eq("id"),eq("SUBMITTING"),eq("RESULT_RECEIVED"),contains("https://example.com/image.png"),isNull());}
    @Test void lostOwnershipCannotOverwriteResult(){assertThrows(IllegalStateException.class,()->ledger.received("org","id",new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult("url","image/png","model","edit",""))));}
}

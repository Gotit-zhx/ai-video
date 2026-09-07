package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignGenerationCoordinatorTest {
    final CommerceDesignAttemptLedger ledger=mock(CommerceDesignAttemptLedger.class);
    final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
    final CommerceDesignGenerationCoordinator coordinator=new CommerceDesignGenerationCoordinator(ledger,gateway,new ObjectMapper());
    final CommerceDesignDraft draft=new CommerceDesignDraft("design","org","capture",1,"request","hash",1,"STOCK_MARKETING","brief","rights","[{\"imageId\":\"image\"}]","DRAFT","u",Instant.now());
    final List<CommerceDesignGenerationCoordinator.PreparedReference> refs=List.of(new CommerceDesignGenerationCoordinator.PreparedReference("image","org/key",new CommerceDesignImageGateway.Reference("image/png","aGVsbG8=")));
    void prepare(boolean claim){when(ledger.prepare(eq("org"),eq("design"),anyList())).thenReturn(new CommerceDesignAttempt("attempt_123","org","design","PREPARED","[]",null,null,Instant.now(),Instant.now()));when(ledger.claim("org","attempt_123")).thenReturn(claim);}
    @Test void replayDoesNotCallProvider(){prepare(false);assertEquals("attempt_123",coordinator.execute(draft,refs));verifyNoInteractions(gateway);}
    @Test void receivedResultSavedAfterClaim(){prepare(true);var result=new CommerceDesignImageGateway.Response(null,new CommerceDesignImageGateway.PendingResult("url","image/png","model","edit",""));when(gateway.generate(eq(draft),anyList(),eq("attempt_123"))).thenReturn(result);coordinator.execute(draft,refs);var order=inOrder(ledger,gateway);order.verify(ledger).prepare(eq("org"),eq("design"),anyList());order.verify(ledger).claim("org","attempt_123");order.verify(gateway).generate(eq(draft),anyList(),eq("attempt_123"));order.verify(ledger).received("org","attempt_123",result);}
    @Test void providerFailureIsUncertainNotAutomaticRetry(){prepare(true);when(gateway.generate(any(),anyList(),anyString())).thenThrow(new IllegalStateException("timeout"));assertThrows(IllegalStateException.class,()->coordinator.execute(draft,refs));verify(ledger).uncertain("org","attempt_123");verify(gateway,times(1)).generate(any(),anyList(),anyString());}
}

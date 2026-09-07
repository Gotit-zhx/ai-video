package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignOutputLedgerTest {
    @Test void outputIsPendingReviewAndOriginalResultIsRetained(){var attempts=mock(CommerceDesignAttemptMapper.class);var outputs=mock(CommerceDesignOutputMapper.class);var ledger=new CommerceDesignOutputLedger(attempts,outputs);when(attempts.lock("org","a")).thenReturn(new CommerceDesignAttempt("a","org","d","RESULT_RECEIVED","[]","original-result",null,Instant.now(),Instant.now()));when(attempts.transition("org","a","RESULT_RECEIVED","OUTPUT_ARCHIVED","original-result",null)).thenReturn(1);var result=ledger.attach("org","a",new CommerceImageArchive.Archived("key","hash","image/png",10));assertEquals("PENDING_REVIEW",result.status());verify(outputs).insert(result);}
}

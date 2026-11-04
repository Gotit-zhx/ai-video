package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceFeeExportJobsTest {
    final Scope owner=new Scope("org","user");
    final Filter filter=FinanceFeeMatrixTest.filter("","");
    final String key="test-key-1234567890";
    @Test void submitReturnsQueuedDeduplicatesAndDownloadIsOwnerBoundAndExpires(){
        var service=mock(FinanceService.class);var queued=new ArrayList<Runnable>();var clock=new AtomicLong(1000);
        when(service.feeExportFacts(companyScope("org"),filter)).thenReturn(FinanceFeeMatrixTest.facts(
                List.of(FinanceFeeMatrixTest.shop("a","","店铺")),List.of(FinanceFeeMatrixTest.fee("a","费用","PHP","10",1,1))));
        var jobs=new FinanceFeeExportJobs(service,clock::get,queued::add,false);
        var created=jobs.submit(owner,filter,key);String id=text(created.get("id"));
        assertEquals("QUEUED",created.get("status"));verifyNoInteractions(service);
        assertEquals(id,jobs.submit(owner,filter,"another-key-12345678").get("id"));assertEquals(1,queued.size());
        assertThrows(RuntimeException.class,()->jobs.download(owner,id));
        queued.getFirst().run();
        assertEquals("READY",jobs.status(owner,id).get("status"));assertTrue(jobs.download(owner,id).bytes().length>0);
        assertTrue(jobs.list(new Scope("org","another")).isEmpty());
        assertThrows(RuntimeException.class,()->jobs.download(new Scope("other-org","user"),id));
        assertThrows(RuntimeException.class,()->jobs.download(new Scope("org","another"),id));
        assertThrows(RuntimeException.class,()->jobs.submit(owner,FinanceFeeMatrixTest.filter("a",""),key));
        assertEquals(id,jobs.submit(owner,filter,key).get("id"));
        clock.addAndGet(61_000);
        assertEquals(id,jobs.submit(owner,filter,"another-key-12345678").get("id"));
        clock.addAndGet(30*60_000);
        assertTrue(jobs.list(owner).isEmpty());assertThrows(RuntimeException.class,()->jobs.download(owner,id));
    }
    @Test void failedQueryIsNotRetriedOrRepresentedAsAnEmptyWorkbook(){
        var service=mock(FinanceService.class);var queued=new ArrayList<Runnable>();
        when(service.feeExportFacts(any(),any())).thenThrow(new IllegalStateException("private database message"));
        var jobs=new FinanceFeeExportJobs(service,System::currentTimeMillis,queued::add,false);
        String id=text(jobs.submit(owner,filter,key).get("id"));queued.getFirst().run();
        var result=jobs.status(owner,id);assertEquals("FAILED",result.get("status"));
        assertFalse(text(result.get("error")).contains("private"));assertThrows(RuntimeException.class,()->jobs.download(owner,id));
        assertEquals(1,queued.size());verify(service,times(1)).feeExportFacts(any(),any());
    }
    @Test void queueLimitsRejectWithoutLosingOrDuplicatingAcceptedJobs(){
        var service=mock(FinanceService.class);var queued=new ArrayList<Runnable>();
        var jobs=new FinanceFeeExportJobs(service,System::currentTimeMillis,queued::add,false);
        jobs.submit(owner,filter,key);
        jobs.submit(owner,FinanceFeeMatrixTest.filter("a",""),"second-key-1234567");
        assertThrows(RuntimeException.class,()->jobs.submit(owner,FinanceFeeMatrixTest.filter("b",""),"third-key-12345678"));
        assertEquals(2,jobs.list(owner).size());assertEquals(2,queued.size());
        var rejected=new FinanceFeeExportJobs(service,System::currentTimeMillis,r->{throw new RejectedExecutionException();},false);
        assertThrows(RuntimeException.class,()->rejected.submit(owner,filter,key));assertTrue(rejected.list(owner).isEmpty());
    }
}

package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceCostExportJobsTest {
    @TempDir Path root;
    final Scope owner=new Scope("org","user");
    final Filter filter=new Filter(java.time.LocalDate.of(2026,10,1),java.time.LocalDate.of(2026,10,31),"","","","day","");
    final AtomicLong clock=new AtomicLong(1000);
    final List<Runnable> queue=new ArrayList<>();
    final FinanceService service=mock(FinanceService.class);
    final FinanceCostExport exporter=mock(FinanceCostExport.class);
    final String key="request-key-123456";
    @AfterEach void releaseQueuedMaintenancePermits(){for(Runnable task:queue)task.run();queue.clear();}
    FinanceCostExportJobs jobs(long fileLimit,long totalLimit){return new FinanceCostExportJobs(service,exporter,clock::get,queue::add,false,root,fileLimit,totalLimit);}
    Map<String,Object> submit(FinanceCostExportJobs jobs,Scope scope,String record,String key){return jobs.submit(scope,filter,"SETTLED_ORDER",record,"ALL","",key);}
    String submit(FinanceCostExportJobs jobs){return text(submit(jobs,owner,"",key).get("id"));}
    void file(int size,String suffix)throws Exception{
        when(exporter.exportFile(any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            assertEquals(companyScope("org"),call.getArgument(0));
            Path path=((Path)call.getArgument(6)).resolve("report."+suffix);
            java.util.function.BiConsumer<Long,Long> progress=call.getArgument(7);progress.accept(7L,10L);
            Files.write(path,new byte[size]);
            return new FinanceCostExport.GeneratedFile(path,"成本报表."+suffix,suffix.equals("zip")?"application/zip":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",10,20,suffix.equals("zip")?2:1);
        });
    }
    @Test void deduplicatesNormalizedSelectionsAndBindsEveryAliasKey()throws Exception{
        file(12,"xlsx");try(var jobs=jobs(100,200)){
            String id=submit(jobs);
            assertEquals(id,jobs.submit(owner,filter," order "," "," all ","", "other-key-12345678").get("id"));
            assertEquals(1,queue.size());
            assertThrows(BusinessException.class,()->submit(jobs,owner,"different",key));
            assertThrows(BusinessException.class,()->submit(jobs,owner,"different","other-key-12345678"));
            queue.removeFirst().run();assertEquals("READY",jobs.status(owner,id).get("status"));
            assertEquals(id,submit(jobs,owner,"","ready-key-12345678").get("id"));
            clock.addAndGet(61_000);
            assertEquals(id,submit(jobs,owner,"",key).get("id"));
            assertNotEquals(id,submit(jobs,owner,"","new-key-123456789").get("id"));
        }
    }
    @Test void statusDownloadAndListingRemainPersonalEvenWithinCompany()throws Exception{
        file(12,"zip");try(var jobs=jobs(100,200)){
            String id=submit(jobs);assertThrows(BusinessException.class,()->jobs.download(owner,id));
            queue.removeFirst().run();
            for(Scope outsider:List.of(new Scope("org","other-user"),new Scope("other-org","user"))){
                assertTrue(jobs.list(outsider).isEmpty());assertThrows(BusinessException.class,()->jobs.status(outsider,id));assertThrows(BusinessException.class,()->jobs.download(outsider,id));
            }
            var result=jobs.status(owner,id);assertEquals(10L,result.get("orders"));assertEquals(20L,result.get("lines"));assertEquals(2,result.get("parts"));assertEquals(10L,result.get("processed"));
            try(var stream=jobs.download(owner,id).stream()){assertEquals(12,stream.readAllBytes().length);}
        }
    }
    @Test void expiryPinsOpenDownloadAndThenDeletesOnlyItsOwnDirectory()throws Exception{
        file(12,"xlsx");Path unrelated=Files.writeString(root.resolve("keep.txt"),"keep");
        try(var jobs=jobs(100,200)){
            String id=submit(jobs);queue.removeFirst().run();var download=jobs.download(owner,id);
            Path directory;try(var paths=Files.list(root)){directory=paths.filter(Files::isDirectory).findFirst().orElseThrow();}
            clock.addAndGet(30*60_000);assertTrue(jobs.list(owner).isEmpty());
            assertTrue(Files.exists(directory));assertEquals(12,download.stream().readAllBytes().length);
            download.stream().close();assertFalse(Files.exists(directory));assertEquals("keep",Files.readString(unrelated));
            assertThrows(BusinessException.class,()->jobs.status(owner,id));
        }
    }
    @Test void ownerAndGlobalLimitsRejectWithoutPhantomJobs(){
        try(var jobs=jobs(100,200)){
            submit(jobs);submit(jobs,owner,"second","second-key-123456");
            assertThrows(BusinessException.class,()->submit(jobs,owner,"third","third-key-1234567"));
            for(int i=0;i<14;i++)submit(jobs,new Scope("org","user-"+i),"",key);
            assertThrows(BusinessException.class,()->submit(jobs,new Scope("org","overflow"),"",key));
            assertEquals(16,queue.size());assertEquals(2,jobs.list(owner).size());
        }
        try(var rejected=new FinanceCostExportJobs(service,exporter,clock::get,r->{throw new RejectedExecutionException();},false,root,100,200)){
            assertThrows(BusinessException.class,()->submit(rejected));assertTrue(rejected.list(owner).isEmpty());
        }
    }
    @Test void aliasKeysAreBounded(){
        try(var jobs=jobs(100,200)){
            submit(jobs);for(int i=0;i<127;i++)submit(jobs,owner,"","alias-request-key-"+i);
            assertThrows(BusinessException.class,()->submit(jobs,owner,"","overflow-alias-key"));assertEquals(1,queue.size());
        }
    }
    @Test void oversizedFilesAndTotalRetentionFailAndRemovePartialFiles()throws Exception{
        file(12,"zip");try(var jobs=jobs(10,200)){
            String id=submit(jobs);queue.removeFirst().run();assertEquals("FAILED",jobs.status(owner,id).get("status"));
            try(var paths=Files.list(root)){assertEquals(0,paths.count());}
        }
        try(var jobs=jobs(100,20)){
            String first=submit(jobs);queue.removeFirst().run();String second=text(submit(jobs,owner,"second","second-key-123456").get("id"));queue.removeFirst().run();
            assertEquals("READY",jobs.status(owner,first).get("status"));assertEquals("FAILED",jobs.status(owner,second).get("status"));
            try(var paths=Files.list(root)){assertEquals(1,paths.count());}
        }
    }
    @Test void failuresDoNotExposeInternalDetailsOrLeaveFiles()throws Exception{
        when(exporter.exportFile(any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{Files.writeString(((Path)call.getArgument(6)).resolve("partial"),"private");throw new IllegalStateException("secret financial data");});
        try(var jobs=jobs(100,200)){
            String id=submit(jobs);queue.removeFirst().run();var state=jobs.status(owner,id);
            assertEquals("FAILED",state.get("status"));assertFalse(text(state.get("error")).contains("secret"));assertThrows(BusinessException.class,()->jobs.download(owner,id));
            try(var paths=Files.list(root)){assertEquals(0,paths.count());}
        }
    }
    @Test void outsideOrSymlinkResultsAreRejectedWithoutDeletingTheirTargets()throws Exception{
        Path external=Files.writeString(root.resolve("external.xlsx"),"private");
        when(exporter.exportFile(any(),any(),anyString(),anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{Path link=((Path)call.getArgument(6)).resolve("link.xlsx");Files.createSymbolicLink(link,external);return new FinanceCostExport.GeneratedFile(link,"bad.xlsx","application/zip",1,1,1);});
        try(var jobs=jobs(100,200)){String id=submit(jobs);queue.removeFirst().run();assertEquals("FAILED",jobs.status(owner,id).get("status"));assertEquals("private",Files.readString(external));}
    }
    @Test void shutdownRejectsSubmissionAndQueuedTasksCannotGenerateFiles()throws Exception{
        file(12,"xlsx");var jobs=jobs(100,200);submit(jobs);jobs.close();queue.removeFirst().run();
        assertThrows(BusinessException.class,()->submit(jobs));try(var paths=Files.list(root)){assertEquals(0,paths.count());}
    }
    @Test void maintenanceGateRejectsWithoutLeavingQueuedTasks(){
        var gate=mock(MaintenanceGate.class);when(gate.closed()).thenReturn(true);
        try(var mocked=mockStatic(MaintenanceGate.class,CALLS_REAL_METHODS);var jobs=jobs(100,200)){
            mocked.when(MaintenanceGate::instance).thenReturn(gate);
            assertThrows(BusinessException.class,()->submit(jobs));assertTrue(jobs.list(owner).isEmpty());assertTrue(queue.isEmpty());
        }
    }
    @Test void unusableTemporaryStorageFailsWithoutReturningReady()throws Exception{
        Path unavailable=Files.writeString(root.resolve("not-directory"),"keep");
        try(var jobs=new FinanceCostExportJobs(service,exporter,clock::get,queue::add,false,unavailable,100,200)){
            String id=submit(jobs);queue.removeFirst().run();assertEquals("FAILED",jobs.status(owner,id).get("status"));assertEquals("keep",Files.readString(unavailable));
        }
    }
    @Test void explicitForeignShopIsRejectedBeforeEnqueue(){
        when(service.ownedShop(companyScope("org"),"foreign")).thenThrow(bad("没有找到本公司的财务店铺"));
        try(var jobs=jobs(100,200)){
            var selected=new Filter(filter.start(),filter.end(),"foreign","","","day","");
            assertThrows(BusinessException.class,()->jobs.submit(owner,selected,"SETTLED_ORDER","","ALL","",key));assertTrue(queue.isEmpty());
        }
    }
}

package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded background batches: page reads never parse historical order JSON or call marketplace APIs. */
@Component
public class FinanceCostWorker {
    final FinanceOrderCosts costs;final FinanceCostPriceScans priceScans;final FinanceCostTargetScans targetScans;final FinanceService service;final TransactionTemplate transactions;
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"finance-standard-costs");thread.setDaemon(true);return thread;});
    public FinanceCostWorker(FinanceRepository repo,FinanceService service,PlatformTransactionManager tx){
        costs=new FinanceOrderCosts(repo);priceScans=new FinanceCostPriceScans(repo);targetScans=new FinanceCostTargetScans(repo);
        repository=repo;this.service=service;transactions=new TransactionTemplate(tx);transactions.setTimeout(30);
    }
    private final FinanceRepository repository;
    private int scanTurn;
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick),20,1,TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
    public void tick(){
        try{
            var changed=new LinkedHashSet<String>();
            var financialChanges=new LinkedHashSet<String>();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            for(int i=0;i<10&&System.nanoTime()<deadline;i++){
                if(i%5==0){
                    int turn=scanTurn;
                    scanTurn=(scanTurn+1)%3;
                    var due=turn==0?targetScans.due():List.<FinanceCostTargetScans.Shop>of();
                    var scanned=due.isEmpty()?null:transactions.execute(status->targetScans.scanBatch(costs,due));
                    if(scanned==null)transactions.execute(status->{
                        if(turn==2||!priceScans.scanBatch(costs))costs.seedAndFill();
                        return null;
                    });
                    if(scanned!=null&&scanned.finished())changed.add(scanned.organization());
                }
                var organizations=processNextBatch(financialChanges);
                if(organizations!=null)changed.addAll(organizations);
                if(organizations==null||organizations.isEmpty())break;
            }
            for(String org:changed){
                var scope=companyScope(org);
                if(financialChanges.contains(org)||!repository.costProgressMode(scope).separated())service.refreshCostSnapshots(scope);
            }
        }catch(RuntimeException error){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Standard cost batch deferred: {}",error.getClass().getSimpleName());
        }
    }
    Set<String> processNextBatch(){return processNextBatch(new LinkedHashSet<>());}
    private Set<String> processNextBatch(Set<String> financialChanges){
        for(int page=0;page<4;page++){
            // The autocommit enumeration must precede the repeatable-read
            // transaction. Financial facts are first read after primary-key
            // claims, so an intervening committed update cannot be lost.
            var candidates=costs.candidateJobs(100,page);
            if(candidates.isEmpty())return Set.of();
            var changed=transactions.execute(status->costs.processBatch(candidates,financialChanges));
            if(changed!=null&&!changed.isEmpty())return changed;
        }
        return Set.of();
    }
}

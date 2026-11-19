package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceWorkspaceQueryWiringTest {
    @Test void springSelectsTheProductionConstructorWhenTheWaitBoundaryConstructorAlsoExists(){
        var repo=mock(FinanceRepository.class);
        try(var context=new AnnotationConfigApplicationContext()){
            context.registerBean(FinanceRepository.class,()->repo);
            context.registerBean(FinanceService.class,()->mock(FinanceService.class));
            context.registerBean(FinanceAdSpendService.class,()->mock(FinanceAdSpendService.class));
            context.registerBean(FinanceQueryProjections.class,()->mock(FinanceQueryProjections.class));
            context.registerBean(FinanceAds.class,()->mock(FinanceAds.class));
            context.registerBean(FinanceReadCache.class,()->mock(FinanceReadCache.class));
            context.register(FinanceWorkspaceQueryService.class);

            context.refresh();

            assertSame(repo,context.getBean(FinanceWorkspaceQueryService.class).repo);
        }
    }
}

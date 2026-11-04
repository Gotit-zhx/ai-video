package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceFeeExportControllerTest {
    final PlatformPrincipal viewer=new PlatformPrincipal("viewer","org","company","name","name","",true,List.of(),List.of("finance:view"));
    final FinanceService service=new FinanceService(mock(FinanceRepository.class),mock(OrganizationAccessPolicy.class),null,null);
    @Test void readOnlyFinanceViewerCanSubmitButAnonymousAndCrossSiteCannot(){
        var controller=new FinanceFeeExportController(service,mock(FinanceFeeExportJobs.class));
        var request=new MockHttpServletRequest("POST","/api/v1/finance/fee-exports");
        request.setServerName("hoyee.org.cn");request.addHeader("Origin","https://hoyee.org.cn");request.addHeader("Sec-Fetch-Site","same-origin");
        assertDoesNotThrow(()->controller.authorize(viewer,request));
        assertThrows(RuntimeException.class,()->controller.authorize(null,request));
        request.removeHeader("Sec-Fetch-Site");request.addHeader("Sec-Fetch-Site","cross-site");
        assertThrows(RuntimeException.class,()->controller.authorize(viewer,request));
    }
    @Test void dashboardRmbSelectionStillQueuesNativeAmountsWithPersonalOwner(){
        var jobs=mock(FinanceFeeExportJobs.class);var controller=new FinanceFeeExportController(service,jobs);
        var body=Map.of("start","2026-10-01","end","2026-10-31","groupId","g","shopId","shop","platform","SHOPEE","currency","PHP","displayCurrency","CNY","fxSnapshot","fixed");
        controller.create(body,"request-id-123456789",viewer);
        verify(jobs).submit(new Scope("org","viewer"),new Filter(java.time.LocalDate.of(2026,10,1),java.time.LocalDate.of(2026,10,31),"shop","SHOPEE","PHP","day","g"),"request-id-123456789");
        assertThrows(RuntimeException.class,()->controller.create(Map.of("start","bad","end","2026-10-31"),"request-id-123456789",viewer));
    }
}

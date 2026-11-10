package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpHeaders;
import java.io.ByteArrayInputStream;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceCostExportControllerTest {
    final PlatformPrincipal viewer=new PlatformPrincipal("viewer","org","company","name","name","",true,List.of(),List.of("finance:view"));
    final FinanceService service=new FinanceService(mock(FinanceRepository.class),mock(OrganizationAccessPolicy.class),null,null);
    @Test void financeViewerCanSubmitAndAnonymousCrossSiteOrForeignOriginCannot(){
        var controller=new FinanceCostExportController(service,mock(FinanceCostExportJobs.class));
        var request=new MockHttpServletRequest("POST","/api/v1/finance/cost-exports");
        request.setScheme("https");request.setServerPort(443);request.setServerName("hoyee.org.cn");request.addHeader("Origin","https://hoyee.org.cn");request.addHeader("Sec-Fetch-Site","same-origin");
        assertDoesNotThrow(()->controller.authorize(viewer,request));assertThrows(RuntimeException.class,()->controller.authorize(null,request));
        request.removeHeader("Sec-Fetch-Site");request.addHeader("Sec-Fetch-Site","cross-site");assertThrows(RuntimeException.class,()->controller.authorize(viewer,request));
        request.removeHeader("Sec-Fetch-Site");request.removeHeader("Origin");request.addHeader("Origin","https://evil.example");assertThrows(RuntimeException.class,()->controller.authorize(viewer,request));
        request.removeHeader("Origin");request.addHeader("Origin","https://hoyee.org.cn:444");assertThrows(RuntimeException.class,()->controller.authorize(viewer,request));
    }
    @Test void selectionAndRealUserArePassedToJobAndDatesValidated(){
        var jobs=mock(FinanceCostExportJobs.class);var controller=new FinanceCostExportController(service,jobs);
        controller.create(Map.of("start","2026-10-01","end","2026-10-31","kind","BILL","recordId","bill-id","shopId","shop","costStatus","PENDING","numbers","A,B"),"request-key-123456",viewer);
        verify(jobs).submit(new Scope("org","viewer"),new Filter(java.time.LocalDate.of(2026,10,1),java.time.LocalDate.of(2026,10,31),"shop","","","day",""),"BILL","bill-id","PENDING","A,B","request-key-123456");
        assertThrows(RuntimeException.class,()->controller.create(Map.of("start","bad","end","2026-10-31"),"request-key-123456",viewer));
        assertThrows(RuntimeException.class,()->controller.status("id",null));
        assertThrows(RuntimeException.class,()->controller.download("id",null));
    }
    @Test void downloadsUseActualMimeLengthUnicodeNameAndClosableStream()throws Exception{
        var jobs=mock(FinanceCostExportJobs.class);var controller=new FinanceCostExportController(service,jobs);
        for(String suffix:List.of("zip","xlsx")){
            String mime=suffix.equals("zip")?"application/zip":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            boolean[] closed={false};var stream=new ByteArrayInputStream(new byte[]{1,2,3}){@Override public void close(){closed[0]=true;}};
            when(jobs.download(new Scope("org","viewer"),"id")).thenReturn(new FinanceCostExportJobs.Download("成本报表."+suffix,mime,3,stream));
            var response=controller.download("id",viewer);
            assertEquals(mime,response.getHeaders().getContentType().toString());assertEquals(3,response.getHeaders().getContentLength());
            assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("filename*=UTF-8''"));
            assertTrue(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL).contains("no-store"));
            try(var input=response.getBody().getInputStream()){assertArrayEquals(new byte[]{1,2,3},input.readAllBytes());}assertTrue(closed[0]);
        }
    }
}

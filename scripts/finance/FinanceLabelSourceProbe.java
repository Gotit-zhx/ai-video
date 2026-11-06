package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.util.*;

/** Read-only parser verification; run with -Xmx96m against the original uploaded workbook. */
public final class FinanceLabelSourceProbe {
    public static void main(String[] args)throws Exception{
        Path scratch=Files.createTempDirectory("label-source-probe-");
        int[] rows={0},failed={0},missing={0};
        long start=System.nanoTime();
        var result=FinanceLabelInvoice.parse(Path.of(args[0]),scratch,row->{
            rows[0]++;if(row.billingPriority()==0)failed[0]++;if(row.orderId().isBlank())missing[0]++;
        });
        System.out.printf(Locale.ROOT,"PASS format=%s rows=%d sourceAmountCny=%s billingFailed=%d blankReferences=%d seconds=%.2f heapLimitMB=%d%n",
            result.type(),rows[0],result.amount().toPlainString(),failed[0],missing[0],(System.nanoTime()-start)/1e9,
            Runtime.getRuntime().maxMemory()/1048576);
    }
}

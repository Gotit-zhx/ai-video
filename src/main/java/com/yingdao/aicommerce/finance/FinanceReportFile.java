package com.yingdao.aicommerce.finance;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
class FinanceReportFile {
    static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final FinanceController ledger;
    private final FinanceAdSpendController ads;
    private final FinanceService finance;
    private final FinanceQianyiReturns returns;

    FinanceReportFile(FinanceController ledger,FinanceAdSpendController ads,FinanceService finance,
                      FinanceQianyiReturns returns){
        this.ledger=ledger;this.ads=ads;this.finance=finance;this.returns=returns;
    }

    byte[] generate(Scope company,Scope owner,FinanceReportSelection selection,FinanceReportReadiness.Status status){
        var f=selection.filter();
        var q=selection.parameters();
        var notes=status.notes(selection);
        return switch(selection.type()){
            case RECORDS->Objects.requireNonNull(ledger.export(company,owner,f,q,notes).getBody());
            case ADVERTISING->Objects.requireNonNull(ads.export(company,f,q,notes).getBody());
            case QIANYI->returns.export(company,q,notes);
            case FEE_MATRIX->FinanceFeeMatrix.workbook(f,finance.feeExportFacts(company,f),notes);
            case ORDER_COST->throw new IllegalArgumentException("Order cost files use the streaming export worker");
        };
    }

    FinanceReportSelection prepareRates(FinanceReportSelection selection){
        if(!selection.cny())return selection;
        var filter=selection.filter();
        String stored=selection.get("fxSnapshot");
        var fx=finance.exchangeRates();
        if(!stored.isBlank()){
            try{fx.historical(stored,filter.start(),filter.end());return selection;}
            catch(com.yingdao.aicommerce.common.exception.BusinessException missing){
                if(!missing.getMessage().contains("历史汇率快照"))throw missing;
            }
        }
        var input=new java.util.TreeMap<>(selection.parameters());
        input.put("fxSnapshot",fx.historical(null,filter.start(),filter.end()).id());
        return new FinanceReportSelection(selection.type(),filter,java.util.Collections.unmodifiableMap(input));
    }

    byte[] generateFromSnapshot(Scope company,Scope owner,FinanceReportSelection selection,FinanceReportReadiness.Status status){
        if(!selection.cny())return generate(company,owner,selection,status);
        return finance.exchangeRates().withStoredExportHistory(selection.get("fxSnapshot"),()->generate(company,owner,selection,status));
    }

    static void addReceipt(FinanceCostWorkbook book,List<List<String>> receipt)throws IOException{
        if(receipt.isEmpty())return;
        var sheet=book.sheet("导出数据状态",receipt.getFirst());
        for(var row:receipt.subList(1,receipt.size()))sheet.row(row);
    }
}

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

    static void addReceipt(FinanceCostWorkbook book,List<List<String>> receipt)throws IOException{
        if(receipt.isEmpty())return;
        var sheet=book.sheet("导出数据状态",receipt.getFirst());
        for(var row:receipt.subList(1,receipt.size()))sheet.row(row);
    }
}

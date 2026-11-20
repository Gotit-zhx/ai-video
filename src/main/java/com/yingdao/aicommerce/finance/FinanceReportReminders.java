package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.ops.MaintenanceGate;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
class FinanceReportReminders {
    private final FinanceRepository repo;
    private final FinanceController guard;
    private final FinanceReportReadiness readiness;

    FinanceReportReminders(FinanceRepository repo,FinanceController guard,FinanceReportReadiness readiness){
        this.repo=repo;this.guard=guard;this.readiness=readiness;
    }

    void subscribe(Scope owner,Scope company,FinanceReportSelection selection){
        var state=readiness.assess(company,selection).state();
        if(!Set.of(FinanceReportReadiness.State.READY,FinanceReportReadiness.State.PROCESSING).contains(state))
            throw bad("请先处理当前范围的缺口，再订阅完成提醒");
        long count=number(repo.jdbc().queryForObject("""
            SELECT COUNT(*) FROM finance_report_export_reminders
            WHERE organization_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(6)
            """,Long.class,owner.organizationId(),owner.userId()));
        if(count>=16)throw bad("提醒已达上限，请先取消不需要的提醒");
        repo.jdbc().update("""
            INSERT INTO finance_report_export_reminders(id,organization_id,user_id,selection_key,report_type,
                selection_json,state,ready_at,expires_at)
            VALUES(?,?,?,?,?,?,?,IF(?='READY',UTC_TIMESTAMP(6),NULL),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY))
            ON DUPLICATE KEY UPDATE
                created_at=UTC_TIMESTAMP(6),selection_json=VALUES(selection_json),
                state=VALUES(state),ready_at=VALUES(ready_at),
                expires_at=VALUES(expires_at),next_check_at=UTC_TIMESTAMP(6)
            """,UUID.randomUUID().toString(),owner.organizationId(),owner.userId(),selection.fingerprint(),selection.type().name(),
            repo.write(selection.parameters()),state==FinanceReportReadiness.State.READY?"READY":"WAITING",
            state.name());
    }

    List<Map<String,Object>> list(Scope owner){
        return repo.jdbc().queryForList("""
            SELECT id,report_type,state,ready_at,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.start')) start_date,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.end')) end_date,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.shopId')) shop_id,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.platform')) platform,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.groupId')) group_id,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.currency')) currency,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.displayCurrency')) display_currency,
                JSON_UNQUOTE(JSON_EXTRACT(selection_json,'$.view')) view_name
            FROM finance_report_export_reminders WHERE organization_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(6)
            ORDER BY created_at DESC LIMIT 16
            """,owner.organizationId(),owner.userId());
    }

    Map<String,Object> readySelection(Scope owner,String id){
        var matching=repo.jdbc().queryForList("""
            SELECT report_type,selection_json FROM finance_report_export_reminders
            WHERE id=? AND organization_id=? AND user_id=? AND state='READY' AND expires_at>UTC_TIMESTAMP(6)
            """,id,owner.organizationId(),owner.userId());
        if(matching.isEmpty())throw FinanceReportExportJobs.conflict("提醒尚未完成、已关闭或已过期");
        var row=matching.getFirst();
        return Map.of("type",text(row.get("report_type")),"selection",repo.read(text(row.get("selection_json"))));
    }

    void dismiss(Scope owner,String id){
        repo.jdbc().update("DELETE FROM finance_report_export_reminders WHERE id=? AND organization_id=? AND user_id=?",
            id,owner.organizationId(),owner.userId());
    }

    @Scheduled(fixedDelay=30_000,initialDelay=30_000)
    void check(){
        MaintenanceGate.runIfOpen(()->{
            repo.jdbc().update("DELETE FROM finance_report_export_reminders WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 64");
            var due=repo.jdbc().queryForList("""
                SELECT id,organization_id,report_type,selection_json FROM finance_report_export_reminders
                WHERE state='WAITING' AND next_check_at<=UTC_TIMESTAMP(6) AND expires_at>UTC_TIMESTAMP(6)
                ORDER BY next_check_at LIMIT 6
                """);
            for(var row:due){
                if(MaintenanceGate.instance().closed())break;
                String id=text(row.get("id")),json=text(row.get("selection_json"));
                int claimed=repo.jdbc().update("""
                    UPDATE finance_report_export_reminders SET next_check_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)
                    WHERE id=? AND state='WAITING' AND next_check_at<=UTC_TIMESTAMP(6)
                    """,id);
                if(claimed==0)continue;
                try{
                    var inputs=new TreeMap<String,String>();
                    repo.read(json).forEach((k,v)->inputs.put(k,text(v)));
                    var selection=FinanceReportSelection.parse(guard,text(row.get("report_type")),inputs);
                    var status=readiness.assess(companyScope(text(row.get("organization_id"))),selection);
                    if(status.ready())repo.jdbc().update("""
                        UPDATE finance_report_export_reminders SET state='READY',ready_at=UTC_TIMESTAMP(6)
                        WHERE id=? AND state='WAITING' AND selection_json=CAST(? AS JSON)
                        """,id,json);
                }catch(RuntimeException unavailable){
                    // A transient status read cannot report readiness.
                }
            }
        });
    }
}

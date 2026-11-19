package com.yingdao.aicommerce.finance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;

/** Request-local true-mode adapter. Every SELECT belongs to the caller's workspace snapshot. */
final class FinanceLabelFirstSettlementFacts {
    private static final Logger LOG=LoggerFactory.getLogger(FinanceLabelFirstSettlementFacts.class);
    private final FinanceRepository repo;
    private final int dirtyCap;
    private final List<ApprovedRange> allowedRanges;
    private final CandidateBudget budget;
    private final LongSupplier clock;

    FinanceLabelFirstSettlementFacts(FinanceRepository repo, int dirtyCap,
                                    List<ApprovedRange> allowedRanges, CandidateBudget budget) {
        this(repo,dirtyCap,allowedRanges,budget,System::nanoTime);
    }

    FinanceLabelFirstSettlementFacts(FinanceRepository repo, int dirtyCap,
                                    List<ApprovedRange> allowedRanges, CandidateBudget budget, LongSupplier clock) {
        this.repo=Objects.requireNonNull(repo);
        if(dirtyCap<0||dirtyCap>1000)throw new IllegalArgumentException("Invalid bounded owner cap");
        this.dirtyCap=dirtyCap;
        this.allowedRanges=List.copyOf(allowedRanges);
        this.allowedRanges.forEach(FinanceLabelFirstSettlementFacts::validateGrant);
        validateBudget(budget);
        this.budget=budget;this.clock=Objects.requireNonNull(clock);
    }

    /** One immutable value is captured at workspace entry, before sourcePage establishes S. */
    record Configuration(Mode mode,int dirtyCap,List<ApprovedRange> allowedRanges,CandidateBudget budget,String error) {
        Configuration {
            Objects.requireNonNull(mode);allowedRanges=List.copyOf(allowedRanges);
            if(dirtyCap<0||dirtyCap>1000)throw new IllegalArgumentException("Invalid bounded owner cap");
            allowedRanges.forEach(FinanceLabelFirstSettlementFacts::validateGrant);validateBudget(budget);
        }
        static Configuration off(){return new Configuration(Mode.OFF,100,List.of(),null,"");}
        static Configuration parse(String mode,String json,int cap,long candidate,long nativeReserve,long completion) {
            return parse(mode,json,Integer.toString(cap),Long.toString(candidate),Long.toString(nativeReserve),Long.toString(completion));
        }
        static Configuration parse(String mode,String json,String cap,String candidate,String nativeReserve,String completion) {
            try {
                int dirtyCap=Integer.parseInt(cap);
                long candidateMillis=Long.parseLong(candidate),nativeReserveMillis=Long.parseLong(nativeReserve),completionReserveMillis=Long.parseLong(completion);
                var selected=Mode.valueOf(mode);
                Object parsed=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readValue(json,Object.class);
                if(!(parsed instanceof List<?> entries))throw new IllegalArgumentException();
                var grants=new ArrayList<ApprovedRange>();
                var keys=Set.of("organizationId","start","end","mode","schemaVersion","registrationVersion","evidenceId");
                for(Object entry:entries) {
                    if(!(entry instanceof Map<?,?> values)||!values.keySet().equals(keys))throw new IllegalArgumentException();
                    String start=string(values,"start"),end=string(values,"end");
                    if(!start.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!end.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();
                    grants.add(new ApprovedRange(string(values,"organizationId"),LocalDate.parse(start),LocalDate.parse(end),
                        Mode.valueOf(string(values,"mode")),integer(values,"schemaVersion"),integer(values,"registrationVersion"),string(values,"evidenceId")));
                }
                var budget=candidateMillis==0&&nativeReserveMillis==0&&completionReserveMillis==0?null:
                    new CandidateBudget(candidateMillis,nativeReserveMillis,completionReserveMillis);
                return new Configuration(selected,dirtyCap,grants,budget,"");
            }catch(Exception invalid) {
                LOG.warn("finance_label_first_settlement configuration=INVALID_CONFIGURATION");
                return new Configuration(Mode.OFF,100,List.of(),null,"INVALID_CONFIGURATION");
            }
        }
        private static String string(Map<?,?> values,String key) {
            if(!(values.get(key) instanceof String value))throw new IllegalArgumentException();return value;
        }
        private static int integer(Map<?,?> values,String key) {
            Object value=values.get(key);
            if(!(value instanceof Integer number)||number<1)throw new IllegalArgumentException();return number;
        }
    }

    private static void validateGrant(ApprovedRange grant) {
        if(grant==null||grant.organizationId()==null||grant.organizationId().isBlank()
            ||!grant.organizationId().equals(grant.organizationId().strip())||grant.organizationId().length()>36
            ||grant.organizationId().contains("*")||grant.organizationId().contains("%")
            ||grant.start()==null||grant.end()==null||grant.start().isAfter(grant.end())
            ||grant.mode()==null||grant.mode()==Mode.OFF||grant.schemaVersion()<1||grant.registrationVersion()<1
            ||grant.evidenceId()==null||grant.evidenceId().isBlank())throw new IllegalArgumentException("Invalid approved range");
    }

    private static void validateBudget(CandidateBudget value) {
        if(value!=null&&(value.candidateMillis()<=0||value.nativeReserveMillis()<=0||value.completionReserveMillis()<=0
            ||value.candidateMillis()>=30000||value.nativeReserveMillis()>=30000||value.completionReserveMillis()>=30000
            ||value.candidateMillis()+value.nativeReserveMillis()+value.completionReserveMillis()>=30000))
            throw new IllegalArgumentException("Invalid candidate budget");
    }

    private boolean preapproved(Mode mode,Scope scope,Filter filter) {
        return mode!=Mode.OFF&&allowedRanges.stream().anyMatch(grant->grant.mode()==mode
            &&grant.organizationId().equals(scope.organizationId())&&!filter.start().isBefore(grant.start())&&!filter.end().isAfter(grant.end()));
    }

    boolean permitted(Mode mode, Scope scope, Filter filter, Registration registration) {
        return mode!=Mode.OFF&&registration!=null&&registration.enabled()&&allowedRanges.stream().anyMatch(grant->
            grant.mode()==mode&&grant.organizationId().equals(scope.organizationId())
            &&grant.schemaVersion()==registration.schemaVersion()&&grant.registrationVersion()==registration.registrationVersion()
            &&!filter.start().isBefore(grant.start())&&!filter.end().isAfter(grant.end()));
    }

    Resolution period(Mode mode, Scope scope, Filter filter) {
        long started=clock.getAsLong();int rows=0;
        if(!preapproved(mode,scope,filter))return resolved(Route.NATIVE,null,mode==Mode.OFF?"OFF":"UNGRANTED",started,rows);
        if(budget==null)return resolved(Route.NATIVE,null,"BUDGET_UNSET",started,rows);
        var read=new ReadBudget(repo,budget,mode,clock);
        try {
            if(!read.canStartCandidate())return fallback(read,"LOCAL_BUDGET",started,rows);
            var registrations=query(read,"""
                SELECT schema_version,registration_version,definition_sha,installation_boundary,enabled
                FROM finance_first_settlement_registration WHERE id=1
                """,(rs,row)->new Registration(rs.getInt(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getBoolean(5)));
            if(registrations.isEmpty())return fallback(read,"REGISTRATION",started,rows);
            var registration=registrations.getFirst();
            if(!supported(registration)||!permitted(mode,scope,filter,registration))return fallback(read,"REGISTRATION",started,rows);
            var guards=query(read,"""
                SELECT organization_id,generation,target_schema_version,registration_version,
                  registration_definition_sha,registration_boundary,seed_complete
                FROM finance_first_settlement_initialization WHERE organization_id=?
                """,(rs,row)->rs.getLong(2)>0&&rs.getInt(3)==registration.schemaVersion()&&rs.getInt(4)==registration.registrationVersion()
                    &&registration.definitionSha().equals(rs.getString(5))&&registration.boundary().equals(rs.getString(6))
                    ?new Guard(rs.getString(1),rs.getLong(2),rs.getInt(3),rs.getInt(4),rs.getBoolean(7)):null,scope.organizationId());
            if(guards.isEmpty()||guards.getFirst()==null)return fallback(read,"GUARD",started,rows);
            var guard=guards.getFirst();
            // Each input is independently bounded, then UNION performs identity comparison using
            // the database columns' collation. Only the combined cap+1 result decides completeness.
            var unresolved=query(read,"""
                SELECT organization_id,shop_id,order_id FROM (
                  (SELECT f.organization_id,f.shop_id,f.order_id
                   FROM finance_first_settlement_facts f FORCE INDEX(idx_fsf_pending)
                   WHERE f.organization_id=? AND f.pending=TRUE LIMIT ?)
                  UNION DISTINCT
                  (SELECT lc.organization_id,lc.shop_id,lc.order_id
                   FROM finance_label_state ls JOIN finance_label_order_totals lc
                     ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
                   LEFT JOIN finance_first_settlement_facts f
                     ON f.organization_id=lc.organization_id AND f.shop_id=lc.shop_id AND f.order_id=lc.order_id
                   WHERE ls.organization_id=? AND (f.organization_id IS NULL OR NOT (
                """+clean("f",guard)+")) LIMIT ?) ) unresolved LIMIT ?",
                (rs,row)->new Owner(rs.getString(1),rs.getString(2),rs.getString(3)),scope.organizationId(),dirtyCap+1,
                scope.organizationId(),dirtyCap+1,dirtyCap+1);
            rows=unresolved.size();
            if(rows>dirtyCap)return fallback(read,"OWNER_CAP",started,rows);
            var candidates=new ArrayList<CandidateRow>();
            for(var owner:unresolved) {
                var candidate=query(read,"""
                    SELECT r0.id,r0.organization_id,r0.shop_id,r0.order_id,r0.record_date,r0.platform,r0.currency,r0.status
                    FROM finance_records r0 FORCE INDEX(idx_finance_first_settlement)
                    WHERE r0.organization_id=? AND r0.shop_id=? AND r0.order_id=? AND
                    """+eligible("r0")+" ORDER BY r0.organization_id,r0.shop_id,r0.kind,r0.order_id,r0.source,r0.status,r0.record_date,r0.id LIMIT 1",
                    (rs,row)->new CandidateRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getDate(5).toLocalDate(),rs.getString(6),rs.getString(7),rs.getString(8)),
                    owner.organizationId(),owner.shopId(),owner.orderId());
                candidates.addAll(candidate);
            }
            StringBuilder relation=new StringBuilder("""
                SELECT f.first_record_id id,lc.organization_id,lc.shop_id,lc.order_id,
                  f.first_record_date record_date,f.first_platform platform,f.first_currency currency,'SETTLED' status
                FROM finance_label_state ls JOIN finance_label_order_totals lc
                  ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
                JOIN finance_first_settlement_facts f
                  ON f.organization_id=lc.organization_id AND f.shop_id=lc.shop_id AND f.order_id=lc.order_id
                WHERE ls.organization_id=? AND f.has_settlement=TRUE AND
                """);
            relation.append(clean("f",guard));
            var args=new ArrayList<Object>();args.add(scope.organizationId());
            for(var candidate:candidates) {
                relation.append(" UNION ALL SELECT ?,?,?,?,CAST(? AS DATE),?,?,?");
                args.add(candidate.id());args.add(candidate.organizationId());args.add(candidate.shopId());args.add(candidate.orderId());
                args.add(java.sql.Date.valueOf(candidate.date()));args.add(candidate.platform());args.add(candidate.currency());args.add(candidate.status());
            }
            var q=repo.query(scope,filter,"r");args.add(scope.organizationId());args.addAll(q.args());
            var totals=query(read,"""
                SELECT COUNT(*) orders,COALESCE(SUM(lc.amount_cny),0) amount_cny,
                  COALESCE(SUM(lc.pending_count),0) conflicts,COALESCE(SUM(lc.shipment_count),0) shipments
                FROM (
                """+relation+") r JOIN finance_label_state ls ON ls.organization_id=r.organization_id "+
                "JOIN finance_label_order_totals lc ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation "+
                "AND lc.shop_id=r.shop_id AND lc.order_id=r.order_id WHERE ls.organization_id=? AND "+
                FinanceOrderCharges.normalOrder("r")+" AND "+q.where(),
                (rs,row)->new Period(rs.getBigDecimal("amount_cny"),rs.getLong("orders"),rs.getLong("conflicts"),rs.getLong("shipments")),args.toArray());
            read.requireUsable();
            return resolved(Route.FACTS,totals.getFirst(),"",started,rows);
        }catch(CandidateExhausted proactiveExit) {
            return fallback(read,"LOCAL_BUDGET",started,rows);
        }
    }

    private boolean supported(Registration proof) {
        return proof.enabled()&&proof.schemaVersion()==SUPPORTED_SCHEMA_VERSION&&proof.registrationVersion()>0
            &&SUPPORTED_DEFINITION_SHA.equals(proof.definitionSha())&&proof.boundary()!=null&&!proof.boundary().isBlank();
    }

    private static String clean(String alias,Guard guard) {
        return alias+".applied_revision="+alias+".requested_revision AND "+alias+".applied_revision>0 AND "+
            alias+".schema_version="+guard.targetSchemaVersion()+" AND "+alias+".applied_generation="+guard.generation()+
            " AND "+alias+".applied_registration_version="+guard.registrationVersion();
    }

    private Resolution fallback(ReadBudget read,String reason,long started,int rows) {
        read.requireUsable();return resolved(Route.NATIVE,null,reason,started,rows);
    }
    private Resolution resolved(Route route,Period value,String reason,long started,int rows) {
        LOG.debug("finance_label_first_settlement route={} reason={} elapsed_ms={} rows={} hash={}",
            route,reason,Math.max(0,(clock.getAsLong()-started)/1_000_000),rows,value==null?"":normalizedHash(value));
        return new Resolution(route,value,reason);
    }
    static String normalizedHash(Period period) {
        return hash(period.amountCny().stripTrailingZeros().toPlainString()+"|"+period.orders()+"|"+period.conflicts()+"|"+period.shipments());
    }
    static boolean equal(Period left,Period right) {
        return left.orders()==right.orders()&&left.conflicts()==right.conflicts()&&left.shipments()==right.shipments()
            &&left.amountCny().compareTo(right.amountCny())==0;
    }

    /** JdbcTemplate applies transaction settings before this callback sets the tighter candidate timeout. */
    private <T> List<T> query(ReadBudget read,String sql,RowMapper<T> mapper,Object...args) {
        if(!read.canStartCandidate())throw new CandidateExhausted();
        return repo.jdbc().execute(connection->{
            var statement=connection.prepareStatement(sql);
            for(int index=0;index<args.length;index++)StatementCreatorUtils.setParameterValue(statement,index+1,SqlTypeValue.TYPE_UNKNOWN,args[index]);
            return statement;
        },(PreparedStatementCallback<List<T>>)statement->{
            read.bound(statement);
            try(var result=statement.executeQuery()) {
                var rows=new ArrayList<T>();int index=0;
                while(result.next())rows.add(mapper.mapRow(result,index++));
                return rows;
            }
        });
    }

    static void requireUsable(FinanceRepository repo) {ReadBudget.holder(Objects.requireNonNull(repo.jdbc().getDataSource()));}

    static final class ReadBudget {
        private final DataSource source;
        private final LongSupplier clock;
        private final long deadlineNanos,reserveMillis;
        ReadBudget(FinanceRepository repo,CandidateBudget budget,Mode mode,LongSupplier clock) {
            source=Objects.requireNonNull(repo.jdbc().getDataSource());this.clock=clock;
            reserveMillis=budget.completionReserveMillis()+(mode==Mode.ACTIVE?budget.nativeReserveMillis():0);
            long remaining=holder(source).getTimeToLiveInMillis()-reserveMillis;
            deadlineNanos=clock.getAsLong()+Math.max(0,Math.min(budget.candidateMillis(),remaining))*1_000_000;
        }
        boolean canStartCandidate(){return remainingMillis()>0;}
        void requireUsable(){holder(source);}
        @SuppressWarnings({"rawtypes","unchecked"})
        void bound(PreparedStatement statement)throws SQLException {
            // JdbcTemplate has already applied its transaction settings. Connector/J exposes
            // millisecond precision on Query; a seconds ceiling would exceed small budgets.
            try {
                Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");
                if(type.isInstance(statement)||statement.isWrapperFor(type)) {
                    Object mysql=type.isInstance(statement)?statement:statement.unwrap(type);
                    Object query=type.getMethod("getQuery").invoke(mysql);
                    Class queryType=Class.forName("com.mysql.cj.Query");
                    var setter=queryType.getMethod("setTimeoutInMillis",long.class);
                    long current=((Number)queryType.getMethod("getTimeoutInMillis").invoke(query)).longValue();
                    long remaining=remainingMillis();if(current>0)remaining=Math.min(remaining,current);
                    if(remaining<1)throw new CandidateExhausted();
                    setter.invoke(query,remaining);return;
                }
            }catch(ClassNotFoundException|NoSuchMethodException unsupported) {
                // Unknown driver/API: use only whole seconds that fit, or skip before execution.
            }catch(ReflectiveOperationException failure) {
                if(failure.getCause() instanceof SQLException sql)throw sql;
                throw new SQLException("Cannot apply the candidate statement timeout",failure);
            }
            long remaining=remainingMillis();int current=statement.getQueryTimeout();
            if(current>0)remaining=Math.min(remaining,current*1000L);
            int seconds=(int)(remaining/1000);if(seconds<1)throw new CandidateExhausted();statement.setQueryTimeout(seconds);
        }
        private long remainingMillis() {
            return Math.min((deadlineNanos-clock.getAsLong())/1_000_000,holder(source).getTimeToLiveInMillis()-reserveMillis);
        }
        private static ConnectionHolder holder(DataSource source) {
            if(!TransactionSynchronizationManager.isActualTransactionActive()
                ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_REPEATABLE_READ)
                ||!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                ||!holder.hasTimeout()||holder.getTimeToLiveInMillis()<=0||holder.isRollbackOnly())
                throw new IllegalStateException("Label workspace snapshot is no longer usable");
            try {
                var connection=holder.getConnection();
                if(connection.isClosed()||connection.getAutoCommit()||!connection.isReadOnly()
                    ||connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)
                    throw new IllegalStateException("Label workspace connection is no longer usable");
            }catch(SQLException failure){throw new DataAccessResourceFailureException("Label workspace connection check failed",failure);}
            return holder;
        }
    }
    private static final class CandidateExhausted extends RuntimeException {}
    private record CandidateRow(String id,String organizationId,String shopId,String orderId,LocalDate date,String platform,String currency,String status) {}
}

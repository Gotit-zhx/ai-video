package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceFirstSettlementFacts.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC", matches=".+")
class FinanceLabelFirstSettlementFactsIntegrationTest extends FinanceFirstSettlementTestSupport {
    static final LocalDate OCTOBER_1=LocalDate.of(2026,10,1), OCTOBER_31=LocalDate.of(2026,10,31);
    static final CandidateBudget BUDGET=new CandidateBudget(10000,10000,2000);
    final Filter october=new Filter(OCTOBER_1,OCTOBER_31,"","","","day","");

    @Test void cleanActiveConsumerUsesPublishedIdentityAndExactCurrentTotals() {
        String shop=groupedShop(owner,"facts-clean",null);
        total(shop,"clean","1.234567",2,3);
        settlement("clean-first",shop,"clean",LocalDate.of(2026,10,2));
        initializeAndPublish();

        var result=resolve(4,october);

        assertEquals(Route.FACTS,result.route(),"A granted clean owner must execute the fact consumer");
        assertPeriod(result.value(),1,"1.234567",2,3);
        assertPeriodEquals(oracle(october),result.value());
    }

    @Test void completeTotalsBudgetDistinguishesKnownEmptyMissingDirtyOldModeAndFullLengthIdentity() {
        String shop=groupedShop(owner,"facts-classify",null);
        total(shop,"missing","2",1,2);settlement("missing-row",shop,"missing",LocalDate.of(2026,10,2));
        total(shop,"dirty","3",7,9);settlement("dirty-row",shop,"dirty",LocalDate.of(2026,10,3));
        total(shop,"old-mode","4",2,4);settlement("old-mode-row",shop,"old-mode",LocalDate.of(2026,10,4));
        String prefix="x".repeat(128), full=prefix+"y".repeat(127);
        total(shop,prefix,"5",0,1);settlement("prefix-row",shop,prefix,LocalDate.of(2026,10,5));
        total(shop,full,"90",0,99);
        total(shop,"known-empty","70",0,99);
        initializeAndPublish();
        db.update("DELETE FROM finance_first_settlement_facts WHERE organization_id='org' AND shop_id=? AND order_id='missing'",shop);
        db.update("UPDATE finance_records SET record_date='2026-09-30' WHERE id='dirty-row'");
        db.update("UPDATE finance_first_settlement_facts SET schema_version=0 WHERE organization_id='org' AND shop_id=? AND order_id='old-mode'",shop);
        db.update("""
            INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id,requested_revision,applied_revision,
              schema_version,applied_generation,applied_registration_version)
            VALUES('org',?,'known-empty',1,1,1,1,1)
            """,shop);
        assertTrue(new FinanceFirstSettlementFacts(repo).guard("org").orElseThrow().seedComplete());

        assertEquals(Route.NATIVE,resolve(2,october).route(),"The combined unresolved set exceeds cap 2");
        assertEquals(Route.NATIVE,resolve(3,october).route(),"Missing plus dirty plus old mode plus long ID exceeds cap 3");
        var result=resolve(4,october);
        assertEquals(Route.FACTS,result.route(),"All four unresolved owners fit cap 4");
        assertPeriod(result.value(),3,"11",3,7);
        assertPeriodEquals(oracle(october),result.value());
        assertEquals(0,ownerRows("org",shop,full),"Reading the long totals ID never persists or truncates it");
        for(int cap:List.of(2,3,4)) {
            var source=new ObservedSource(dataSource);
            var labels=labels(source,configuration(Mode.ACTIVE,cap,grants(Mode.ACTIVE)),System::nanoTime);
            var page=labels.workspace(owner,october,Map.of());
            assertPeriod(period(page),3,"11",3,7);
            assertPeriodEquals(oracle(october),period(page));
            assertEquals(cap==4?1:0,source.count(ObservedSource::factAggregate));
            assertEquals(cap==4?0:1,source.count(ObservedSource::nativePeriod));
            if(cap==4)assertTrue(source.bindings.stream().anyMatch(values->values.contains(full)),"The full 255-character ID must reach its native lookup");
        }
    }

    @Test void actualActiveWorkspaceExecutesFactsWithOneBoundConnectionAndTighterStatementTimeouts() {
        String shop=groupedShop(owner,"active-workspace",null);
        total(shop,"clean","1.234567",2,3);settlement("clean-first",shop,"clean",LocalDate.of(2026,10,2));
        initializeAndPublish();sourceFile(shop,"clean");
        var source=new ObservedSource(dataSource);
        var labels=labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime);

        var page=labels.workspace(owner,october,Map.of());

        assertPeriod(period(page),1,"1.234567",2,3);
        assertPeriodEquals(oracle(october),period(page));
        assertPeriodEquals(nativePeriod(october),period(page));
        assertEquals(1,source.count(ObservedSource::factAggregate));
        assertEquals(0,source.count(ObservedSource::nativePeriod));
        assertEquals(0,source.count(ObservedSource::lookup),"A clean owner uses the copied eight-column relation");
        assertEquals(1,number(page.get("total")));assertEquals(1,rows(page.get("files")).size());assertNotNull(page.get("state"));
        source.assertSnapshot();
        assertTrue(source.executed.stream().filter(row->ObservedSource.candidate(row.sql())).allMatch(row->row.timeout()>0&&row.timeout()<=10),
            "The callback must retain the tighter candidate timeout after JdbcTemplate applies its 30-second settings");
        assertTrue(source.prepared.stream().allMatch(sql->sql.stripLeading().startsWith("SELECT")),"A request cannot write shared fact or initialization state");
    }

    @Test void unionUsesDatabaseOwnerCollationAndTheCombinedCapRatherThanJavaStringEquality() {
        String shop=groupedShop(owner,"collation-union",null);
        total(shop,"ECLAIR","2",0,1);settlement("accent-first",shop,"Éclair",LocalDate.of(2026,10,2));
        total(shop,"missing","3",0,1);settlement("missing-first",shop,"missing",LocalDate.of(2026,10,3));
        initializeAndPublish();
        db.update("UPDATE finance_records SET currency='USD' WHERE id='accent-first'");
        db.update("DELETE FROM finance_first_settlement_facts WHERE order_id='missing'");
        var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.ACTIVE,2,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertPeriod(period(page),2,"5",0,2);
        assertEquals(1,source.count(ObservedSource::factAggregate));
        assertEquals(2,source.count(ObservedSource::lookup),"Éclair and ECLAIR represent one unresolved owner under the source collation");
        assertPeriodEquals(oracle(october),period(page));
        var capped=new ObservedSource(dataSource);
        assertPeriod(period(labels(capped,configuration(Mode.ACTIVE,1,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of())),2,"5",0,2);
        assertEquals(1,capped.count(ObservedSource::nativePeriod));assertEquals(0,capped.count(ObservedSource::lookup));
    }

    @Test void companyPendingOwnersOutsideTheRequestedMonthAndTotalsStillConsumeAndResolveBudget() {
        String shop=groupedShop(owner,"company-pending",null);
        total(shop,"inside","2",0,1);settlement("inside-first",shop,"inside",LocalDate.of(2026,10,2));
        total(shop,"moved","3",0,1);settlement("moved-first",shop,"moved",LocalDate.of(2026,10,3));
        settlement("no-total-first",shop,"no-total",LocalDate.of(2026,8,2));
        initializeAndPublish();
        db.update("UPDATE finance_records SET record_date='2026-09-30' WHERE id='moved-first'");
        db.update("UPDATE finance_records SET currency='USD' WHERE id='no-total-first'");
        var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.ACTIVE,2,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertPeriod(period(page),1,"2",0,1);assertPeriodEquals(oracle(october),period(page));
        assertEquals(2,source.count(ObservedSource::lookup));assertEquals(1,source.count(ObservedSource::factAggregate));
        assertTrue(source.bindings.stream().anyMatch(values->values.contains("no-total")));
        var capped=new ObservedSource(dataSource);
        labels(capped,configuration(Mode.ACTIVE,1,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertEquals(1,capped.count(ObservedSource::nativePeriod));
    }

    @Test void cleanKnownEmptyAndImportedRealZeroRemainDistinctWithoutNativeOwnerLookup() {
        String shop=groupedShop(owner,"known-empty-zero",null);
        total(shop,"zero","0",0,1);settlement("zero-first",shop,"zero",LocalDate.of(2026,10,2));
        total(shop,"empty","90",0,99);initializeAndPublish();
        db.update("INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id,requested_revision,applied_revision,schema_version,applied_generation,applied_registration_version) VALUES('org',?,'empty',1,1,1,1,1)",shop);
        var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.ACTIVE,1,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertPeriod(period(page),1,"0",0,1);assertPeriodEquals(oracle(october),period(page));
        assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::lookup));
    }

    @Test void grantsAreExactPerCompanyDateAndModeAndNewSeedNeverActivatesAnotherCompany() {
        String shop=groupedShop(owner,"grant-a",null),otherShop=groupedShop(other,"grant-b",null);
        total(shop,"a","2",0,1);settlement("a-first",shop,"a",LocalDate.of(2026,10,2));
        total(other,otherShop,"b","3",0,1);insertSettlement("b-first","other-org",otherShop,"b","SHOPEE","settlement","SETTLED","API",LocalDate.of(2026,10,2),"PHP");
        initializeAndPublish();initializeAndPublish("other-org");
        var allowed=grants(Mode.ACTIVE);
        var a=new ObservedSource(dataSource);var active=labels(a,configuration(Mode.ACTIVE,5,allowed),System::nanoTime);
        assertPeriod(period(active.workspace(owner,october,Map.of())),1,"2",0,1);assertEquals(1,a.count(ObservedSource::factAggregate));
        var b=new ObservedSource(dataSource);
        assertPeriod(period(labels(b,configuration(Mode.ACTIVE,5,allowed),System::nanoTime).workspace(other,october,Map.of())),1,"3",0,1);
        assertNoNewTables(b);
        Scope fresh=new Scope("company-c","c");String freshShop=groupedShop(fresh,"grant-c",null);
        total(fresh,freshShop,"c","4",0,1);insertSettlement("c-first","company-c",freshShop,"c","SHOPEE","settlement","SETTLED","API",LocalDate.of(2026,10,2),"PHP");
        initializeAndPublish("company-c");assertTrue(new FinanceFirstSettlementFacts(repo).guard("company-c").orElseThrow().seedComplete());
        var c=new ObservedSource(dataSource);
        assertPeriod(period(labels(c,configuration(Mode.ACTIVE,5,allowed),System::nanoTime).workspace(fresh,october,Map.of())),1,"4",0,1);assertNoNewTables(c);
        for(var outside:List.of(new Filter(LocalDate.of(2026,9,30),OCTOBER_31,"","","","day"),
                new Filter(OCTOBER_1,LocalDate.of(2026,11,1),"","","","day"))) {
            var source=new ObservedSource(dataSource);
            labels(source,configuration(Mode.ACTIVE,5,allowed),System::nanoTime).workspace(owner,outside,Map.of());assertNoNewTables(source);
        }
        var empty=new ObservedSource(dataSource);
        labels(empty,configuration(Mode.ACTIVE,5,List.of()),System::nanoTime).workspace(owner,october,Map.of());assertNoNewTables(empty);
        var shadowMiss=new ObservedSource(dataSource);
        labels(shadowMiss,configuration(Mode.SHADOW,5,allowed),System::nanoTime).workspace(owner,october,Map.of());assertNoNewTables(shadowMiss);
        var shadow=new ObservedSource(dataSource);
        assertPeriod(period(labels(shadow,configuration(Mode.SHADOW,5,grants(Mode.SHADOW)),System::nanoTime).workspace(owner,october,Map.of())),1,"2",0,1);
        assertEquals(1,shadow.count(ObservedSource::nativePeriod));assertEquals(1,shadow.count(ObservedSource::factAggregate));shadow.assertSnapshot();
        var factsBefore=db.queryForList("SELECT * FROM finance_first_settlement_facts ORDER BY organization_id,shop_id,order_id");
        var proofBefore=db.queryForList("SELECT * FROM finance_first_settlement_registration");
        var initBefore=db.queryForList("SELECT * FROM finance_first_settlement_initialization ORDER BY organization_id");
        labels(new ObservedSource(dataSource),configuration(Mode.OFF,5,List.of()),System::nanoTime).workspace(owner,october,Map.of());
        assertEquals(factsBefore,db.queryForList("SELECT * FROM finance_first_settlement_facts ORDER BY organization_id,shop_id,order_id"));
        assertEquals(proofBefore,db.queryForList("SELECT * FROM finance_first_settlement_registration"));
        assertEquals(initBefore,db.queryForList("SELECT * FROM finance_first_settlement_initialization ORDER BY organization_id"));
    }

    @Test void grantsCannotCombineDateIntervalsOrVersionPairsAndConfigurationCopiesItsList() {
        String shop=groupedShop(owner,"grant-scope",null);total(shop,"one","2",0,1);settlement("one-first",shop,"one",OCTOBER_1);initializeAndPublish();
        var split=List.of(new ApprovedRange("org",OCTOBER_1,LocalDate.of(2026,10,15),Mode.ACTIVE,1,1,"first-half"),
            new ApprovedRange("org",LocalDate.of(2026,10,16),OCTOBER_31,Mode.ACTIVE,1,1,"second-half"));
        var splitSource=new ObservedSource(dataSource);
        labels(splitSource,configuration(Mode.ACTIVE,4,split),System::nanoTime).workspace(owner,october,Map.of());assertNoNewTables(splitSource);
        var versions=List.of(new ApprovedRange("org",OCTOBER_1,OCTOBER_31,Mode.ACTIVE,1,2,"schema-only"),
            new ApprovedRange("org",OCTOBER_1,OCTOBER_31,Mode.ACTIVE,2,1,"registration-only"));
        var versionSource=new ObservedSource(dataSource);
        labels(versionSource,configuration(Mode.ACTIVE,4,versions),System::nanoTime).workspace(owner,october,Map.of());
        assertEquals(1,versionSource.count(ObservedSource::nativePeriod));assertEquals(0,versionSource.count(ObservedSource::factAggregate));
        var mutable=new ArrayList<ApprovedRange>();var frozen=configuration(Mode.ACTIVE,4,mutable);mutable.addAll(grants(Mode.ACTIVE));
        var source=new ObservedSource(dataSource);
        labels(source,frozen,System::nanoTime).workspace(owner,october,Map.of());assertNoNewTables(source);
        var exactCase=new ObservedSource(dataSource);
        labels(exactCase,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(new Scope("ORG","u"),october,Map.of());assertNoNewTables(exactCase);
    }

    @Test void strictConfigurationRejectsMalformedGrantsAndUnconfiguredBudgetWithoutNewTableReads() {
        String shop=groupedShop(owner,"parse-grants",null);total(shop,"one","2",0,1);settlement("one-first",shop,"one",OCTOBER_1);initializeAndPublish();
        String valid="[{\"organizationId\":\"org\",\"start\":\"2026-10-01\",\"end\":\"2026-10-31\",\"mode\":\"ACTIVE\",\"schemaVersion\":1,\"registrationVersion\":1,\"evidenceId\":\"synthetic\"}]";
        for(String invalid:List.of("{}",valid.replace("org","*"),valid.replace("2026-10-31","2026-09-30"),
                valid.replace("\"end\":\"2026-10-31\",",""),valid.replace("\"ACTIVE\"","\"OFF\""),
                valid.replace("\"synthetic\"","\"\""),valid.replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),
                valid.replace("\"start\":\"2026-10-01\"","\"start\":null"),valid.replace("\"evidenceId\"","\"unexpected\""),
                valid.replace("\"organizationId\":\"org\"","\"organizationId\":\"other-org\",\"organizationId\":\"org\""),valid+" []")) {
            var configuration=FinanceLabelFirstSettlementFacts.Configuration.parse("ACTIVE",invalid,4,10000,10000,2000);
            assertEquals("INVALID_CONFIGURATION",configuration.error());
            var source=new ObservedSource(dataSource);
            assertPeriod(period(labels(source,configuration,System::nanoTime).workspace(owner,october,Map.of())),1,"2",0,1);assertNoNewTables(source);
        }
        var source=new ObservedSource(dataSource);
        var unconfigured=FinanceLabelFirstSettlementFacts.Configuration.parse("ACTIVE",valid,4,0,0,0);
        assertNull(unconfigured.budget());labels(source,unconfigured,System::nanoTime).workspace(owner,october,Map.of());assertNoNewTables(source);
        assertEquals("INVALID_CONFIGURATION",FinanceLabelFirstSettlementFacts.Configuration.parse("active",valid,4,10000,10000,2000).error());
        assertEquals("INVALID_CONFIGURATION",FinanceLabelFirstSettlementFacts.Configuration.parse("ACTIVE",valid,4,10000,10000,10000).error());
        var validSource=new ObservedSource(dataSource);
        try(var binding=bindLabels(validSource,springProperties(Mode.ACTIVE))) {
            assertPeriod(period(binding.labels().workspace(owner,october,Map.of())),1,"2",0,1);
            assertEquals(1,validSource.count(ObservedSource::factAggregate));
        }
    }

    @Test void disabledMissingOrIncompatibleProofAndCompanyGuardUseCompleteNativePeriod() {
        String shop=groupedShop(owner,"proof-guard",null);total(shop,"one","2",0,1);settlement("one-first",shop,"one",OCTOBER_1);initializeAndPublish();
        for(String assignment:List.of("enabled=FALSE","definition_sha='unknown'","schema_version=99","registration_version=2","installation_boundary=''")) {
            db.update("UPDATE finance_first_settlement_registration SET "+assignment+" WHERE id=1");assertNativeComplete();
            db.update("UPDATE finance_first_settlement_registration SET enabled=TRUE,definition_sha=?,schema_version=1,registration_version=1,installation_boundary='disposable-runner-install' WHERE id=1",triggerDefinitionSha());
        }
        for(String assignment:List.of("generation=0","generation=2","target_schema_version=99","registration_version=2","registration_definition_sha='unknown'","registration_boundary='different'")) {
            db.update("UPDATE finance_first_settlement_initialization SET "+assignment+" WHERE organization_id='org'");
            var source=new ObservedSource(dataSource);var page=labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
            assertPeriod(period(page),1,"2",0,1);assertPeriodEquals(oracle(october),period(page));
            // A valid new generation with old applied rows is resolved precisely within cap.
            assertEquals(assignment.equals("generation=2")?1:0,source.count(ObservedSource::factAggregate));
            db.update("UPDATE finance_first_settlement_initialization SET generation=1,target_schema_version=1,registration_version=1,registration_definition_sha=?,registration_boundary='disposable-runner-install' WHERE organization_id='org'",triggerDefinitionSha());
        }
        db.update("DELETE FROM finance_first_settlement_initialization WHERE organization_id='org'");assertNativeComplete();
        db.update("DELETE FROM finance_first_settlement_registration");assertNativeComplete();
    }

    @Test void allFiltersAndReportingWindowsMatchBothIndependentOriginalAndPreservedNativeQueries() {
        var fixture=semanticFixture();
        var cases=List.of(
            new Expected(october,12,"87.234566",3,15),
            new Expected(selected(OCTOBER_1,OCTOBER_31,"","","PHP",""),10,"76.999999",1,11),
            new Expected(selected(OCTOBER_1,OCTOBER_31,"","","USD",""),2,"10.234567",2,4),
            new Expected(selected(OCTOBER_1,OCTOBER_31,"","TIKTOK_SHOP","",""),2,"9",0,2),
            new Expected(selected(OCTOBER_1,OCTOBER_31,fixture.shopA(),"","",""),9,"40.234566",3,12),
            new Expected(selected(OCTOBER_1,OCTOBER_31,"","","",fixture.groupA()),9,"40.234566",3,12),
            new Expected(selected(OCTOBER_1,OCTOBER_31,"","","",fixture.groupB()),1,"8",0,1),
            new Expected(selected(OCTOBER_1,OCTOBER_31,"","","","UNGROUPED"),2,"39",0,2),
            new Expected(selected(OCTOBER_1,LocalDate.of(2026,10,15),"","","",""),11,"83.234566",3,14),
            new Expected(selected(LocalDate.of(2026,9,1),OCTOBER_31,"","","",""),13,"99.234566",3,16));
        var wide=List.of(new ApprovedRange("org",LocalDate.of(2026,9,1),LocalDate.of(2026,11,30),Mode.ACTIVE,1,1,"synthetic-all-filters"));
        for(var expected:cases) {
            var source=new ObservedSource(dataSource);
            var page=labels(source,configuration(Mode.ACTIVE,100,wide),System::nanoTime).workspace(owner,expected.filter(),Map.of());
            assertPeriod(period(page),expected.orders(),expected.amount(),expected.conflicts(),expected.shipments());
            assertPeriodEquals(oracle(expected.filter()),period(page));assertPeriodEquals(nativePeriod(expected.filter()),period(page));
            assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));source.assertSnapshot();
        }
    }

    @Test void sourceDeletionAndFirstDateMovingAcrossMonthsRemoveOldContributionsBeforeRepublishing() {
        String shop=groupedShop(owner,"delete-move",null);total(shop,"one","11",1,2);
        settlement("a-first",shop,"one",LocalDate.of(2026,10,2));settlement("z-second",shop,"one",LocalDate.of(2026,10,3));initializeAndPublish();
        var wide=List.of(new ApprovedRange("org",LocalDate.of(2026,9,1),LocalDate.of(2026,11,30),Mode.ACTIVE,1,1,"synthetic-moves"));
        assertFacts(october,wide,1,"11",1,2);
        db.update("DELETE FROM finance_records WHERE id='a-first'");assertFacts(october,wide,1,"11",1,2);
        db.update("UPDATE finance_records SET record_date='2026-11-01' WHERE id='z-second'");
        assertFacts(october,wide,0,"0",0,0);
        var november=selected(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),"","","","");
        assertFacts(november,wide,1,"11",1,2);initializeAndPublish();
        assertFacts(october,wide,0,"0",0,0);assertFacts(november,wide,1,"11",1,2);
        db.update("DELETE FROM finance_records WHERE id='z-second'");assertFacts(november,wide,0,"0",0,0);initializeAndPublish();
        var source=new ObservedSource(dataSource);
        assertPeriod(period(labels(source,configuration(Mode.ACTIVE,4,wide),System::nanoTime).workspace(owner,november,Map.of())),0,"0",0,0);
        assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::lookup));
        assertFalse(db.queryForObject("SELECT has_settlement FROM finance_first_settlement_facts WHERE order_id='one'",Boolean.class));
    }

    @Test void unchangedWinnerIdDateWithNewPlatformCurrencyIsResolvedBeforeThoseFilters() {
        String shop=groupedShop(owner,"copy-fields",null);total(shop,"one","11",0,1);settlement("first",shop,"one",LocalDate.of(2026,10,2));initializeAndPublish();
        long before=revision("org",shop,"one");
        db.update("UPDATE finance_records SET platform='TIKTOK_SHOP',category='ORDER',currency='USD' WHERE id='first'");
        assertTrue(revision("org",shop,"one")>before);
        for(boolean clean:List.of(false,true)) {
            if(clean)initializeAndPublish();
            assertFacts(selected(OCTOBER_1,OCTOBER_31,"","","PHP",""),grants(Mode.ACTIVE),0,"0",0,0);
            assertFacts(selected(OCTOBER_1,OCTOBER_31,"","","USD",""),grants(Mode.ACTIVE),1,"11",0,1);
            assertFacts(selected(OCTOBER_1,OCTOBER_31,"","TIKTOK_SHOP","",""),grants(Mode.ACTIVE),1,"11",0,1);
            assertFacts(selected(OCTOBER_1,OCTOBER_31,"","SHOPEE","",""),grants(Mode.ACTIVE),0,"0",0,0);
        }
        assertEquals("first",db.queryForObject("SELECT first_record_id FROM finance_first_settlement_facts WHERE order_id='one'",String.class));
    }

    @Test void rawReportingProvenanceCorrectionsAndMarketingRemainLiveWithoutPointerRevision() {
        String shop=groupedShop(owner,"read-time-provenance",null);total(shop,"one","4",0,1);
        insertSettlement("first","org",shop,"one","TIKTOK_SHOP","ORDER","SETTLED","API",OCTOBER_1,"PHP");
        db.update("UPDATE finance_records SET statement_id='bill' WHERE id='first'");
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES('org',?,'one','bill','2026-10-01','USER_CONFIRMED_MONTH','test','u')",shop);
        initializeAndPublish();long revision=revision("org",shop,"one");
        var partial=selected(OCTOBER_1,LocalDate.of(2026,10,15),"","","","");
        assertFacts(partial,grants(Mode.ACTIVE),1,"4",0,1);
        db.update("UPDATE finance_records SET raw_json=? WHERE id='first'",repo.write(Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-30")));
        assertEquals(revision,revision("org",shop,"one"));
        assertFacts(partial,grants(Mode.ACTIVE),0,"0",0,0);assertFacts(october,grants(Mode.ACTIVE),1,"4",0,1);
        db.update("DELETE FROM finance_reporting_months WHERE organization_id='org'");assertEquals(revision,revision("org",shop,"one"));assertFacts(partial,grants(Mode.ACTIVE),1,"4",0,1);
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES('org',?,'one',1,1)",shop);
        assertEquals(revision,revision("org",shop,"one"));assertFacts(october,grants(Mode.ACTIVE),0,"0",0,0);
        db.update("DELETE FROM finance_marketing_orders WHERE organization_id='org'");assertFacts(october,grants(Mode.ACTIVE),1,"4",0,1);
    }

    @Test void incompleteSeedStillClassifiesTheWholeCurrentTotalsAndQueuedGenerationIsNotCleanliness() {
        String shop=groupedShop(owner,"seed-not-complete",null);total(shop,"one","2",0,1);settlement("first",shop,"one",OCTOBER_1);
        var init=new FinanceFirstSettlementInitialization(repo);init.discoverCompanies(100);
        assertFalse(new FinanceFirstSettlementFacts(repo).guard("org").orElseThrow().seedComplete());
        assertFacts(october,grants(Mode.ACTIVE),1,"2",0,1);
        initializeAndPublish();
        db.update("UPDATE finance_first_settlement_facts SET queued_generation=0 WHERE order_id='one'");
        var source=new ObservedSource(dataSource);
        labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertEquals(0,source.count(ObservedSource::lookup));assertEquals(1,source.count(ObservedSource::factAggregate));
        for(String assignment:List.of("applied_revision=0","schema_version=0","applied_generation=0","applied_registration_version=0")) {
            db.update("UPDATE finance_first_settlement_facts SET "+assignment+" WHERE order_id='one'");
            var dirty=new ObservedSource(dataSource);
            assertPeriod(period(labels(dirty,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of())),1,"2",0,1);
            assertEquals(1,dirty.count(ObservedSource::lookup));assertEquals(1,dirty.count(ObservedSource::factAggregate));
            db.update("UPDATE finance_first_settlement_facts SET applied_revision=requested_revision,schema_version=1,applied_generation=1,applied_registration_version=1 WHERE order_id='one'");
        }
    }

    @Test void activeLocalBudgetExitFallsBackInsideTheOriginalCompleteWorkspaceSnapshot() throws Exception {
        localBudgetFallback(Mode.ACTIVE);
    }
    @Test void shadowLocalBudgetExitReturnsItsOriginalNativeSnapshotAndFinishesState() throws Exception {
        localBudgetFallback(Mode.SHADOW);
    }
    void localBudgetFallback(Mode mode)throws Exception {
        String shop=snapshotFixture();var clock=new AtomicLong();var source=new ObservedSource(dataSource);
        source.pauseAfter=sql->sql.contains("FROM finance_first_settlement_registration");
        source.afterQuery=sql->{if(sql.contains("FROM finance_first_settlement_registration")){clock.addAndGet(TimeUnit.SECONDS.toNanos(10));source.afterQuery=ignored->{};}};
        var page=whileMutating(source,labels(source,configuration(mode,4,grants(mode)),clock::get),october,()->publishNext(shop));
        assertOldWorkspace(page);assertEquals(1,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::factAggregate));source.assertSnapshot();
        assertEquals(1,source.count(sql->sql.startsWith("SELECT requested_revision,applied_revision,error_message")));
        var nextSource=new ObservedSource(dataSource);
        var next=labels(nextSource,configuration(mode,4,grants(mode)),clock::get).workspace(owner,october,Map.of());
        assertNewWorkspace(next);assertEquals(1,nextSource.count(ObservedSource::factAggregate));
    }

    @Test void candidateStartUsesActualHolderRemainingTimeAndDriverMillisecondPrecision() {
        snapshotFixture();
        var source=new ObservedSource(dataSource);
        source.afterQuery=sql->{if(sql.contains("source_name,status,source_type"))source.boundHolder.setTimeoutInMillis(5000);};
        var page=labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertOldWorkspace(page);assertNoNewTables(source);
        assertTrue(source.executed.stream().filter(row->ObservedSource.nativePeriod(row.sql())).allMatch(row->row.timeout()>0&&row.timeout()<=5),
            "The candidate cannot restart the workspace deadline at the period stage");
        assertEquals(1,source.connections);
        var shortSource=new ObservedSource(dataSource);
        var tiny=new FinanceLabelFirstSettlementFacts.Configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE),new CandidateBudget(999,10000,2000),"");
        assertOldWorkspace(labels(shortSource,tiny,System::nanoTime).workspace(owner,october,Map.of()));
        assertEquals(1,shortSource.count(ObservedSource::factAggregate));assertEquals(0,shortSource.count(ObservedSource::nativePeriod));
    }

    @Test void realClockPlannedSubsecondBudgetsExecuteTheCompleteFactRouteWithEffectiveMillisecondBounds() {
        snapshotFixture();
        for(long candidate:List.of(1000L,500L,250L,100L)) {
            var source=new ObservedSource(dataSource);
            var configuration=new FinanceLabelFirstSettlementFacts.Configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE),new CandidateBudget(candidate,10000,2000),"");
            var page=labels(source,configuration,System::nanoTime).workspace(owner,october,Map.of());
            assertOldWorkspace(page);assertEquals(1,source.count(ObservedSource::factAggregate),"The planned "+candidate+" ms budget must run FACTS on the fast isolated fixture");
            assertEquals(0,source.count(ObservedSource::nativePeriod));source.assertSnapshot();
            assertTrue(source.executed.stream().filter(row->ObservedSource.candidate(row.sql()))
                .allMatch(row->row.timeoutMillis()>0&&row.timeoutMillis()<=candidate),"The effective driver bound must fit the actual millisecond budget");
        }
    }

    @Test void statementPreparationDelayTightensEffectiveTimeoutAgainstLocalAndOriginalHolderReserves() {
        snapshotFixture();
        for(boolean holderBound:List.of(false,true)) {
            var source=new ObservedSource(dataSource);
            long candidate=holderBound?1000:500,delay=holderBound?100:200,maximum=holderBound?150:300;
            if(holderBound)source.afterQuery=sql->{if(sql.contains("source_name,status,source_type"))source.boundHolder.setTimeoutInMillis(12250);};
            source.afterPrepare=sql->{if(sql.contains("FROM finance_first_settlement_registration")) {
                try{Thread.sleep(delay);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            }};
            var configuration=new FinanceLabelFirstSettlementFacts.Configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE),new CandidateBudget(candidate,10000,2000),"");
            assertOldWorkspace(labels(source,configuration,System::nanoTime).workspace(owner,october,Map.of()));
            assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));
            var registration=source.executed.stream().filter(row->row.sql().contains("FROM finance_first_settlement_registration")).findFirst().orElseThrow();
            assertTrue(registration.timeoutMillis()>0&&registration.timeoutMillis()<=maximum,
                "Preparation time must reduce the final bound after JdbcTemplate's transaction settings");
            assertEquals(1,source.connections);
        }
    }

    @Test void driverNaturallyTimesOutTheBoundCandidateSqlAndAbortsActiveAndShadow() {
        snapshotFixture();
        for(var mode:List.of(Mode.ACTIVE,Mode.SHADOW)) {
            var source=new ObservedSource(dataSource);
            source.sqlTransform=sql->sql.contains("FROM finance_first_settlement_registration")?sql+" AND SLEEP(2)=0":sql;
            var configuration=new FinanceLabelFirstSettlementFacts.Configuration(mode,4,grants(mode),new CandidateBudget(250,10000,2000),"");
            var failure=assertThrows(RuntimeException.class,()->labels(source,configuration,System::nanoTime).workspace(owner,october,Map.of()),
                "The driver must cancel the actual slow MySQL statement within the candidate bound");
            assertTrue(causedBy(failure,SQLTimeoutException.class),"Natural cancellation must remain a SQL timeout, not proactive fallback");
            var query=source.executed.stream().filter(row->row.sql().contains("SLEEP(2)")).findFirst().orElseThrow();
            assertTrue(query.timeoutMillis()>0&&query.timeoutMillis()<=250);
            assertEquals(mode==Mode.SHADOW?1:0,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::factAggregate));
            assertEquals(0,source.count(sql->sql.startsWith("SELECT requested_revision,applied_revision,error_message")));assertEquals(1,source.connections);
        }
    }

    @Test void zeroDirtyCapAllowsCleanAndKnownEmptyFactsThroughRealSpringPropertyBinding() {
        String shop=snapshotFixture();total(shop,"known-empty","99",0,99);
        db.update("INSERT INTO finance_first_settlement_facts(organization_id,shop_id,order_id,requested_revision,applied_revision,schema_version,applied_generation,applied_registration_version) VALUES('org',?,'known-empty',1,1,1,1,1)",shop);
        var source=new ObservedSource(dataSource);var properties=springProperties(Mode.ACTIVE);properties.put("finance.first-settlement-facts.dirty-cap","0");
        try(var binding=bindLabels(source,properties)) {
            assertOldWorkspace(binding.labels().workspace(owner,october,Map.of()));
            assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::lookup));
            assertEquals(1,source.count(sql->sql.contains(") unresolved LIMIT")));source.assertSnapshot();
        }
    }

    @Test void zeroDirtyCapFindsTheFirstUnresolvedOwnerThenReturnsTheWholeNativePeriodWithoutLookup() {
        String shop=snapshotFixture();total(shop,"missing","2",1,2);settlement("missing-first",shop,"missing",LocalDate.of(2026,10,3));
        db.update("DELETE FROM finance_first_settlement_facts WHERE order_id='missing'");
        var source=new ObservedSource(dataSource);var properties=springProperties(Mode.ACTIVE);properties.put("finance.first-settlement-facts.dirty-cap","0");
        try(var binding=bindLabels(source,properties)) {
            var page=binding.labels().workspace(owner,october,Map.of());
            assertPeriod(period(page),2,"13",1,3);assertPeriodEquals(oracle(october),period(page));assertPeriodEquals(nativePeriod(october),period(page));
            assertEquals(1,source.count(sql->sql.contains(") unresolved LIMIT")),"A valid zero cap must classify one owner rather than switching the whole configuration OFF");
            assertEquals(0,source.count(ObservedSource::factAggregate));assertEquals(1,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::lookup));
            assertTrue(source.bindings.stream().anyMatch(values->Collections.frequency(values,1)==3),"Each bounded union input and the combined limit must receive cap+1=1");
            source.assertSnapshot();
        }
    }

    @Test void realSpringMalformedAndOverflowingNumericPropertiesFailClosedWhileNativeWorkspaceRemainsAvailable() {
        snapshotFixture();var cases=new LinkedHashMap<String,List<String>>();
        cases.put("dirty-cap",List.of("not-a-number","2147483648"));
        cases.put("candidate-millis",List.of("not-a-number","9223372036854775808"));
        cases.put("native-reserve-millis",List.of("not-a-number","9223372036854775808"));
        cases.put("completion-reserve-millis",List.of("not-a-number","9223372036854775808"));
        var assertions=new ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var mode:List.of(Mode.OFF,Mode.ACTIVE))for(var entry:cases.entrySet())for(var invalid:entry.getValue())assertions.add(()->{
            var source=new ObservedSource(dataSource);var properties=springProperties(mode);properties.put("finance.first-settlement-facts."+entry.getKey(),invalid);
            assertDoesNotThrow(()->{
                try(var binding=bindLabels(source,properties)) {
                    assertEquals("INVALID_CONFIGURATION",binding.labels().firstSettlementConfiguration.error());
                    assertOldWorkspace(binding.labels().workspace(owner,october,Map.of()));assertNoNewTables(source);source.assertSnapshot();
                }
            },"Spring must bind raw "+entry.getKey()+" inside the guarded configuration boundary even in "+mode);
        });
        assertAll("Real Spring numeric-property binding",assertions);
        var defaults=new ObservedSource(dataSource);
        try(var binding=bindLabels(defaults,Map.of())) {
            assertOldWorkspace(binding.labels().workspace(owner,october,Map.of()));assertNoNewTables(defaults);
            assertEquals("",binding.labels().firstSettlementConfiguration.error());
        }
    }

    @Test void unexposedMillisecondDriverCapabilitySkipsShortSqlAndNeverRoundsARepresentableBoundUp() {
        snapshotFixture();
        for(long candidate:List.of(250L,1500L)) {
            var source=new ObservedSource(dataSource);source.hideMillisecondSupport=true;
            var configuration=new FinanceLabelFirstSettlementFacts.Configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE),new CandidateBudget(candidate,10000,2000),"");
            assertOldWorkspace(labels(source,configuration,System::nanoTime).workspace(owner,october,Map.of()));
            if(candidate<1000) {
                assertEquals(0,source.count(sql->sql.contains("finance_first_settlement_")));assertEquals(1,source.count(ObservedSource::nativePeriod));
            }else {
                assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));
                assertTrue(source.executed.stream().filter(row->ObservedSource.candidate(row.sql())).allMatch(row->row.timeoutMillis()>0&&row.timeoutMillis()<=candidate));
            }
        }
    }

    @Test void expiredRealHolderAbortsActiveAndShadowWithoutNativeFallbackOrPartialResponse() throws Exception {
        holderFailure("EXPIRED");
    }
    @Test void rollbackOnlyRealHolderAbortsActiveAndShadowWithoutNativeFallbackOrPartialResponse() throws Exception {
        holderFailure("ROLLBACK_ONLY");
    }
    @Test void closedRealConnectionAbortsActiveAndShadowWithoutNewPeriodTransaction() throws Exception {
        holderFailure("CLOSED");
    }
    void holderFailure(String fault)throws Exception {
        snapshotFixture();
        for(var mode:List.of(Mode.ACTIVE,Mode.SHADOW)) {
            var source=new ObservedSource(dataSource);source.pauseBefore=sql->sql.contains("FROM finance_first_settlement_registration");
            var labels=labels(source,configuration(mode,4,grants(mode)),System::nanoTime);
            assertThrows(RuntimeException.class,()->whileMutating(source,labels,october,()->{
                try {
                    switch(fault) {
                        case "EXPIRED"->source.boundHolder.setTimeoutInMillis(0);
                        case "ROLLBACK_ONLY"->source.boundHolder.setRollbackOnly();
                        case "CLOSED"->{
                            var physical=source.boundConnection.unwrap(Connection.class);
                            assertTrue(physical.getClass().getName().startsWith("com.mysql."),"Fault injection must close the physical MySQL connection");
                            physical.close();assertTrue(physical.isClosed());
                        }
                        default->throw new AssertionError();
                    }
                }catch(SQLException failure){throw new IllegalStateException(failure);}
            }));
            assertEquals(mode==Mode.SHADOW?1:0,source.count(ObservedSource::nativePeriod),"An invalid RR must never run a fallback period");
            assertEquals(0,source.count(ObservedSource::factAggregate));
            assertEquals(0,source.count(sql->sql.startsWith("SELECT requested_revision,applied_revision,error_message")));
            assertEquals(1,source.connections,"No replacement period transaction or connection is allowed");assertTrue(source.rollbacks>0);
        }
    }

    @Test void candidateSqlTimeoutPropagatesInActiveAndShadowInsteadOfBecomingLocalBudgetExit() {
        snapshotFixture();
        for(var mode:List.of(Mode.ACTIVE,Mode.SHADOW)) {
            var source=new ObservedSource(dataSource);
            source.beforeQuery=sql->{if(sql.contains(") unresolved LIMIT"))throw new SQLTimeoutException("injected bounded candidate timeout","HYT00");};
            var failure=assertThrows(RuntimeException.class,()->labels(source,configuration(mode,4,grants(mode)),System::nanoTime).workspace(owner,october,Map.of()));
            assertTrue(causedBy(failure,SQLTimeoutException.class));
            assertEquals(mode==Mode.SHADOW?1:0,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::factAggregate));
            assertEquals(0,source.count(sql->sql.startsWith("SELECT requested_revision,applied_revision,error_message")));assertEquals(1,source.connections);
        }
    }

    @Test void arbitraryCandidateSqlErrorAlsoAbortsAndShadowCannotHideAStateFailure() {
        snapshotFixture();
        var broken=new ObservedSource(dataSource);
        broken.beforeQuery=sql->{if(sql.contains("FROM finance_first_settlement_initialization"))throw new SQLException("injected candidate error","42000");};
        var failure=assertThrows(RuntimeException.class,()->labels(broken,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of()));
        assertTrue(causedBy(failure,SQLException.class));assertEquals(0,broken.count(ObservedSource::nativePeriod));assertEquals(1,broken.connections);
        var state=new ObservedSource(dataSource);
        state.beforeQuery=sql->{if(sql.startsWith("SELECT requested_revision,applied_revision,error_message"))throw new SQLException("injected final state failure","08006");};
        assertThrows(RuntimeException.class,()->labels(state,configuration(Mode.SHADOW,4,grants(Mode.SHADOW)),System::nanoTime).workspace(owner,october,Map.of()));
        assertEquals(1,state.count(ObservedSource::nativePeriod));assertEquals(1,state.count(ObservedSource::factAggregate));assertEquals(1,state.connections);
    }

    @Test void transactionInvalidatedAfterCandidateStillDiscardsTheWholeWorkspace() {
        snapshotFixture();
        for(var mode:List.of(Mode.ACTIVE,Mode.SHADOW)) {
            var source=new ObservedSource(dataSource);
            source.afterQuery=sql->{if(ObservedSource.factAggregate(sql))source.boundHolder.setRollbackOnly();};
            assertThrows(RuntimeException.class,()->labels(source,configuration(mode,4,grants(mode)),System::nanoTime).workspace(owner,october,Map.of()));
            assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(mode==Mode.SHADOW?1:0,source.count(ObservedSource::nativePeriod));
            assertEquals(0,source.count(sql->sql.startsWith("SELECT requested_revision,applied_revision,error_message")));assertEquals(1,source.connections);
        }
    }

    @Test void detailsOnlyKeepsSnapshotBoundaryWithoutAnyPeriodOrNewFactQuery() {
        snapshotFixture();var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of("detailsOnly","true"));
        assertEquals(Set.of("rows","total","page","pageSize"),page.keySet());assertEquals(1,number(page.get("total")));source.assertSnapshot();
        assertFalse(source.prepared.stream().anyMatch(sql->sql.contains("finance_first_settlement_")));
        assertEquals(0,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::factAggregate));
        assertFalse(source.prepared.stream().anyMatch(sql->sql.startsWith("SELECT requested_revision,applied_revision,error_message")));
        assertThrows(IllegalStateException.class,()->new FinanceLabelFirstSettlementFacts(repo,4,grants(Mode.ACTIVE),BUDGET).period(Mode.ACTIVE,owner,october));
    }

    @Test void realReaderKeepsOldGenerationAndCompleteWorkspaceWhenAnotherConnectionPublishes() throws Exception {
        String shop=snapshotFixture();var source=new ObservedSource(dataSource);
        source.pauseAfter=sql->sql.startsWith("SELECT id FROM finance_label_imports");
        var page=whileMutating(source,labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime),october,()->publishNext(shop));
        assertOldWorkspace(page);assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));source.assertSnapshot();
        var nextSource=new ObservedSource(dataSource);
        assertNewWorkspace(labels(nextSource,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of()));assertEquals(1,nextSource.count(ObservedSource::factAggregate));
    }

    @Test void publicationAfterUnresolvedClassificationDoesNotDuplicateOrOmitDirtyOwner() throws Exception {
        String shop=snapshotFixture();db.update("UPDATE finance_records SET currency='USD' WHERE id='snapshot-first'");
        var facts=new FinanceFirstSettlementFacts(repo);var lease=facts.claim("org",UUID.randomUUID().toString(),30).orElseThrow();var computed=facts.calculate(lease);
        var source=new ObservedSource(dataSource);source.pauseAfter=sql->sql.contains(") unresolved LIMIT");
        var page=whileMutating(source,labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime),october,()->assertTrue(facts.publish(computed)));
        assertPeriod(period(page),1,"11",0,1);assertEquals(1,source.count(ObservedSource::lookup));assertEquals(1,source.count(ObservedSource::factAggregate));source.assertSnapshot();
        var next=new ObservedSource(dataSource);
        assertPeriod(period(labels(next,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of())),1,"11",0,1);
        assertEquals(0,next.count(ObservedSource::lookup));assertEquals(1,next.count(ObservedSource::factAggregate));
        assertEquals(revision("org",shop,"snapshot"),db.queryForObject("SELECT applied_revision FROM finance_first_settlement_facts WHERE order_id='snapshot'",Long.class));
    }

    @Test void dirtyingCleanOwnerAfterClassificationPreservesSameDayWinnerAndOldMonthUntilNextRequest() throws Exception {
        String shop=snapshotFixture();
        db.update("UPDATE finance_records SET currency='USD' WHERE id='snapshot-first'");settlement("snapshot-z",shop,"snapshot",LocalDate.of(2026,10,2));initializeAndPublish();
        var source=new ObservedSource(dataSource);source.pauseAfter=sql->sql.contains(") unresolved LIMIT");
        var wide=List.of(new ApprovedRange("org",LocalDate.of(2026,9,1),OCTOBER_31,Mode.ACTIVE,1,1,"synthetic-concurrent-month"));
        var usd=selected(OCTOBER_1,OCTOBER_31,"","","USD","");
        var page=whileMutating(source,labels(source,configuration(Mode.ACTIVE,4,wide),System::nanoTime),usd,
            ()->db.update("UPDATE finance_records SET record_date='2026-09-30',currency='PHP' WHERE id='snapshot-first'"));
        assertPeriod(period(page),1,"11",0,1);assertEquals(0,source.count(ObservedSource::lookup));assertEquals(1,source.count(ObservedSource::factAggregate));source.assertSnapshot();
        assertFacts(usd,wide,0,"0",0,0);assertFacts(october,wide,0,"0",0,0);
        assertFacts(selected(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","PHP",""),wide,1,"11",0,1);
    }

    @Test void initializationCompletedByAnotherConnectionCannotChangeTheReadersMissingGuardDecision() throws Exception {
        String shop=groupedShop(owner,"init-race",null);total(shop,"snapshot","11",0,1);settlement("snapshot-first",shop,"snapshot",LocalDate.of(2026,10,2));sourceFile(shop,"snapshot");
        var source=new ObservedSource(dataSource);source.pauseAfter=sql->sql.startsWith("SELECT id FROM finance_label_imports");
        var page=whileMutating(source,labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime),october,this::initializeAndPublish);
        assertOldWorkspace(page);assertEquals(1,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::factAggregate));source.assertSnapshot();
        var next=new ObservedSource(dataSource);
        assertOldWorkspace(labels(next,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of()));assertEquals(1,next.count(ObservedSource::factAggregate));
    }

    @Test void partialCandidateLookupBudgetExitDiscardsAllCandidateWorkAndReturnsCompleteNativeTotals() {
        String shop=groupedShop(owner,"partial-budget",null);
        total(shop,"one","2.123456",1,2);settlement("one",shop,"one",LocalDate.of(2026,10,2));
        total(shop,"two","-1.000001",2,3);settlement("two",shop,"two",LocalDate.of(2026,10,3));initializeAndPublish();
        db.update("DELETE FROM finance_first_settlement_facts");
        var clock=new AtomicLong();var source=new ObservedSource(dataSource);
        source.afterQuery=sql->{if(ObservedSource.lookup(sql))clock.addAndGet(TimeUnit.SECONDS.toNanos(10));};
        var page=labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),clock::get).workspace(owner,october,Map.of());
        assertPeriod(period(page),2,"1.123455",3,5);assertPeriodEquals(oracle(october),period(page));assertPeriodEquals(nativePeriod(october),period(page));
        assertEquals(1,source.count(ObservedSource::lookup));assertEquals(0,source.count(ObservedSource::factAggregate));assertEquals(1,source.count(ObservedSource::nativePeriod));source.assertSnapshot();
    }

    @Test void deadlineIsCheckedAgainAfterJdbcTemplateSettingsBeforeExecutingEachCandidateStatement() throws Exception {
        snapshotFixture();var clock=new AtomicLong();var source=new ObservedSource(dataSource);
        source.pauseBefore=sql->sql.contains("FROM finance_first_settlement_registration");
        var page=whileMutating(source,labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),clock::get),october,
            ()->clock.addAndGet(TimeUnit.SECONDS.toNanos(10)));
        assertOldWorkspace(page);assertEquals(0,source.count(sql->sql.contains("finance_first_settlement_")));
        assertEquals(1,source.count(ObservedSource::nativePeriod));source.assertSnapshot();
    }

    @Test void readerRequiresAnActualReadOnlyRepeatableReadHolderWithADeadline() {
        for(int invalid=0;invalid<3;invalid++) {
            var template=new org.springframework.transaction.support.TransactionTemplate(tx);
            template.setReadOnly(invalid!=0);
            template.setIsolationLevel(invalid==1?org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED:
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            if(invalid!=2)template.setTimeout(30);
            assertThrows(IllegalStateException.class,()->template.execute(status->
                new FinanceLabelFirstSettlementFacts(repo,4,grants(Mode.ACTIVE),BUDGET).period(Mode.ACTIVE,owner,october)));
        }
    }

    @Test void futureFalseAdapterRejoinsEveryEligibleFirstDayRowAndRetainsPerRowReportingCurrencyAndPlatform() {
        String shop=groupedShop(owner,"false-evidence",null);total(shop,"one","2",0,1);
        detailSettlement("a-first",shop,"one",OCTOBER_1,"TIKTOK_SHOP","ORDER","SETTLED","API","USD",
            Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-30"));
        detailSettlement("z-same-day",shop,"one",OCTOBER_1,"SHOPEE","settlement","SETTLED","API","PHP",Map.of());
        detailSettlement("fee-ineligible",shop,"one",LocalDate.of(2026,9,30),"TIKTOK_SHOP","FEE","SETTLED","API","PHP",Map.of());
        settlement("later",shop,"one",LocalDate.of(2026,10,2));
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES('org',?,'one','a-first','2026-10-01','USER_CONFIRMED_MONTH','test','u')",shop);
        initializeAndPublish();
        var filters=List.of(october,selected(OCTOBER_1,OCTOBER_31,"","","PHP",""),selected(OCTOBER_1,OCTOBER_31,"","","USD",""),
            selected(OCTOBER_1,OCTOBER_31,"","SHOPEE","",""),selected(OCTOBER_1,LocalDate.of(2026,10,15),"","","",""));
        for(var filter:filters) {
            var q=repo.query(owner,filter,"r");
            var original=db.queryForList("SELECT r.id,r.platform,r.currency,r.record_date FROM finance_records r WHERE "+
                FinanceCostReport.firstSettlementIdentity("r",false)+" AND "+FinanceOrderCharges.normalOrder("r")+" AND "+q.where()+" ORDER BY r.id",q.args().toArray());
            var rejoined=db.queryForList("""
                SELECT r.id,r.platform,r.currency,r.record_date FROM finance_first_settlement_facts f
                JOIN finance_records r ON r.organization_id=f.organization_id AND r.shop_id=f.shop_id AND r.order_id=f.order_id
                  AND r.record_date=f.first_record_date
                WHERE f.has_settlement=TRUE AND
                """+FinanceFirstSettlementFacts.eligible("r")+" AND "+FinanceOrderCharges.normalOrder("r")+" AND "+q.where()+" ORDER BY r.id",q.args().toArray());
            assertEquals(original,rejoined);
        }
        var php=repo.query(owner,selected(OCTOBER_1,OCTOBER_31,"","","PHP",""),"r");
        assertEquals(List.of("z-same-day"),db.queryForList("SELECT r.id FROM finance_records r WHERE "+FinanceCostReport.firstSettlement("r",false)+" AND "+php.where(),String.class,php.args().toArray()));
        assertTrue(db.queryForList("SELECT r.id FROM finance_records r WHERE "+FinanceCostReport.firstSettlement("r",true)+" AND "+php.where(),String.class,php.args().toArray()).isEmpty(),
            "Single-winner true mode cannot replace false mode's same-day PHP row");
        assertEquals("USD",db.queryForObject("SELECT first_currency FROM finance_first_settlement_facts WHERE order_id='one'",String.class));
    }

    @Test void originalFalseRelatedQueriesPreserveDistinctAndMultipleOrderRefundSources() {
        String shop=groupedShop(owner,"false-related",null);
        detailSettlement("a-first",shop,"one",OCTOBER_1,"SHOPEE","settlement","SETTLED","API","USD",Map.of());
        detailSettlement("z-same-day",shop,"one",OCTOBER_1,"SHOPEE","settlement","SETTLED","API","PHP",Map.of());
        for(String kind:List.of("ORDER","REFUND"))for(int row=1;row<=2;row++) {
            String id=kind.toLowerCase(Locale.ROOT)+"-"+row;
            db.update("""
                INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,
                  category,status,record_date,currency,amount,source,raw_json)
                VALUES(?,'org','u',?,'SHOPEE',?,?,'one','','COMPLETED','2026-09-01','PHP',1,'API','{}')
                """,id,shop,kind,id);
        }
        for(String kind:List.of("SETTLED_ORDER","REFUND_SETTLED","SETTLEMENT","REFUND")) {
            var q=repo.query(owner,october,"r");
            // REFUND is source-dated and therefore uses its actual source month.
            if(kind.equals("REFUND"))q=repo.query(owner,selected(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","",""),"r");
            var records=db.queryForList(FinanceCostReport.related(kind)+q.where(),q.args().toArray());
            assertEquals(2,records.size(),"First-day multi-currency rows must not multiply original source records");
            assertEquals(Set.of("order-1","order-2"),
                new HashSet<>(records.stream().map(row->text(row.get("order_record_id"))).toList()));
        }
        assertEquals(List.of("refund-1","refund-2"),db.queryForList("SELECT id FROM finance_records WHERE kind='REFUND' ORDER BY id",String.class),
            "Both original refund sources remain in the ledger while the cost relation deduplicates related order IDs");
    }

    @Test void explicitlyGrantedSqlEquivalentCompanyUsesDatabaseGuardAndOwnerIdentity() {
        String shop=groupedShop(owner,"guard-collation",null);total(shop,"one","2",0,1);settlement("first",shop,"one",OCTOBER_1);initializeAndPublish();
        var alternate=new Scope("ÓRG","u");
        var approved=List.of(new ApprovedRange("ÓRG",OCTOBER_1,OCTOBER_31,Mode.ACTIVE,1,1,"synthetic-exact-alternate-grant"));
        var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.ACTIVE,4,approved),System::nanoTime).workspace(alternate,october,Map.of());
        assertPeriod(period(page),1,"2",0,1);assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));
        assertPeriodEquals(oracle(october),period(page));source.assertSnapshot();
    }

    @Test void shadowReturnsCompleteNativeResponseEvenWhenSyntheticPointerDriftMakesItsCandidateDifferent() {
        snapshotFixture();
        // Deliberately broken isolated pointer: proves SHADOW response ownership, not equivalence.
        db.update("UPDATE finance_first_settlement_facts SET first_record_date='2026-09-30' WHERE order_id='snapshot'");
        var candidate=repo.readSnapshot(30,()->new FinanceLabelFirstSettlementFacts(repo,4,grants(Mode.SHADOW),BUDGET).period(Mode.SHADOW,owner,october));
        assertEquals(Route.FACTS,candidate.route());assertPeriod(candidate.value(),0,"0",0,0);
        var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.SHADOW,4,grants(Mode.SHADOW)),System::nanoTime).workspace(owner,october,Map.of());
        assertOldWorkspace(page);assertPeriodEquals(oracle(october),period(page));assertPeriodEquals(nativePeriod(october),period(page));
        assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(1,source.count(ObservedSource::nativePeriod));source.assertSnapshot();
    }

    String snapshotFixture() {
        String shop=groupedShop(owner,"snapshot-active",null);total(shop,"snapshot","11",0,1);
        settlement("snapshot-first",shop,"snapshot",LocalDate.of(2026,10,2));initializeAndPublish();sourceFile(shop,"snapshot");return shop;
    }
    void publishNext(String shop) {
        repo.transaction(()->{
            db.update("UPDATE finance_label_invoice_rows SET match_note='new-note',amount_cny=9 WHERE import_id='old-file'");
            db.update("INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note,match_checked) VALUES('old-file',2,'org','event-2','row-2','bucket-2','snapshot','shop','customer','warehouse','doc','2026-08-01',9,?,'MATCHED','second-note',TRUE)",shop);
            db.update("UPDATE finance_label_imports SET source_name='new.xlsx',status='FAILED',row_count=2,matched_count=2,amount_cny=18 WHERE id='old-file'");
            db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES('org','new-generation',?,'snapshot',22,2,3)",shop);
            db.update("UPDATE finance_label_state SET active_generation='new-generation',requested_revision=2,applied_revision=1,error_message='new-state' WHERE organization_id='org'");return null;
        });
    }
    void assertOldWorkspace(Map<String,Object> page) {
        assertEquals(1,number(page.get("total")));assertEquals(1,rows(page.get("rows")).size());
        assertEquals("old-note",rows(page.get("rows")).getFirst().get("match_note"));assertEquals("old.xlsx",rows(page.get("files")).getFirst().get("source_name"));
        assertEquals("READY",rows(page.get("rows")).getFirst().get("import_status"));assertPeriod(period(page),1,"11",0,1);
        assertEquals(1,number(map(page.get("state")).get("requested_revision")));assertEquals(false,page.get("processing"));
    }
    void assertNewWorkspace(Map<String,Object> page) {
        assertEquals(2,number(page.get("total")));assertEquals("new.xlsx",rows(page.get("files")).getFirst().get("source_name"));
        assertEquals("FAILED",rows(page.get("rows")).getFirst().get("import_status"));assertPeriod(period(page),1,"22",2,3);
        assertEquals(2,number(map(page.get("state")).get("requested_revision")));assertEquals(true,page.get("processing"));
    }
    Map<String,Object> whileMutating(ObservedSource source,FinanceLabelCosts labels,Filter filter,Runnable mutation)throws Exception {
        var executor=Executors.newSingleThreadExecutor();Future<Map<String,Object>> future=null;
        try {
            future=executor.submit(()->labels.workspace(owner,filter,Map.of()));
            assertTrue(source.reached.await(10,TimeUnit.SECONDS),"The actual workspace did not reach its database latch");
            long otherId=db.queryForObject("SELECT CONNECTION_ID()",Long.class);
            assertFalse(source.executed.stream().anyMatch(row->row.connectionId()==otherId),"Writer and reader must hold different real database connections");
            mutation.run();source.release.countDown();
            try{return future.get(20,TimeUnit.SECONDS);}
            catch(ExecutionException failure){
                if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                if(failure.getCause() instanceof AssertionError assertion)throw assertion;throw failure;
            }
        }finally {
            source.release.countDown();if(future!=null&&!future.isDone())future.cancel(true);executor.shutdownNow();
            assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));assertEquals(0,dataSource.getHikariPoolMXBean().getActiveConnections());
        }
    }
    boolean causedBy(Throwable failure,Class<? extends Throwable> type) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(type.isInstance(cause))return true;return false;
    }

    void assertFacts(Filter filter,List<ApprovedRange> grants,long orders,String amount,long conflicts,long shipments) {
        var source=new ObservedSource(dataSource);
        var page=labels(source,configuration(Mode.ACTIVE,100,grants),System::nanoTime).workspace(owner,filter,Map.of());
        assertPeriod(period(page),orders,amount,conflicts,shipments);assertPeriodEquals(oracle(filter),period(page));assertPeriodEquals(nativePeriod(filter),period(page));
        assertEquals(1,source.count(ObservedSource::factAggregate));assertEquals(0,source.count(ObservedSource::nativePeriod));
    }

    SemanticFixture semanticFixture() {
        String groupA=service.saveGroup(owner,null,"Fact A"),groupB=service.saveGroup(owner,null,"Fact B");
        String shopA=groupedShop(owner,"semantic-a",groupA),shopB=groupedShop(owner,"semantic-b",groupB);
        String ungrouped=groupedShop(owner,"semantic-ungrouped",null),removed=groupedShop(owner,"semantic-removed",groupA);
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removed);
        total(shopA,"same-day","1.234567",2,3);
        detailSettlement("same-day-a",shopA,"same-day",LocalDate.of(2026,10,2),"SHOPEE","settlement","SETTLED","API","USD",Map.of());
        detailSettlement("same-day-z",shopA,"same-day",LocalDate.of(2026,10,2),"SHOPEE","settlement","SETTLED","API","PHP",Map.of());
        total(shopA,"zero","0",0,1);settlement("zero",shopA,"zero",LocalDate.of(2026,10,3));
        total(shopA,"negative","-2.000001",1,2);settlement("negative",shopA,"negative",LocalDate.of(2026,10,4));
        total(shopA,"tiktok","5",0,1);
        detailSettlement("tiktok-invalid",shopA,"tiktok",LocalDate.of(2026,9,30),"TIKTOK_SHOP","FEE","SETTLED","API","PHP",Map.of());
        detailSettlement("tiktok-valid",shopA,"tiktok",LocalDate.of(2026,10,5),"TIKTOK_SHOP","ORDER","SETTLED","API","PHP",Map.of());
        total(shopA,"non-api","6",0,1);
        detailSettlement("manual-invalid",shopA,"non-api",LocalDate.of(2026,9,30),"SHOPEE","settlement","SETTLED","IMPORT","PHP",Map.of());settlement("api-valid",shopA,"non-api",LocalDate.of(2026,10,6));
        total(shopA,"pending","7",0,1);
        detailSettlement("pending-invalid",shopA,"pending",LocalDate.of(2026,9,30),"SHOPEE","settlement","PENDING","API","PHP",Map.of());
        detailSettlement("cancelled-invalid",shopA,"pending",LocalDate.of(2026,9,29),"SHOPEE","settlement","CANCELLED","API","PHP",Map.of());settlement("settled-valid",shopA,"pending",LocalDate.of(2026,10,7));
        total(shopB,"other-group","8",0,1);settlement("other-group",shopB,"other-group",LocalDate.of(2026,10,8));
        total(shopA,"currency","9",0,1);
        detailSettlement("currency-first",shopA,"currency",LocalDate.of(2026,10,9),"SHOPEE","settlement","SETTLED","API","USD",Map.of());settlement("currency-later",shopA,"currency",LocalDate.of(2026,10,10));
        total(shopA,"platform","10",0,1);settlement("platform-first",shopA,"platform",LocalDate.of(2026,10,11));
        detailSettlement("platform-later",shopA,"platform",LocalDate.of(2026,10,12),"TIKTOK_SHOP","ORDER","SETTLED","API","PHP",Map.of());
        total(shopA,"outside","12",0,1);settlement("outside-first",shopA,"outside",LocalDate.of(2026,9,20));settlement("outside-later",shopA,"outside",LocalDate.of(2026,10,13));
        total(shopA,"marked","13",0,1);settlement("marked",shopA,"marked",LocalDate.of(2026,10,14));
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES('org',?,'marked',1,1)",shopA);
        total(shopA,"missing","14",0,1);total(removed,"removed","15",0,1);settlement("removed",removed,"removed",LocalDate.of(2026,10,14));
        db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES('org','retired',?,'retired',17,0,1)",shopA);
        settlement("retired",shopA,"retired",LocalDate.of(2026,10,14));
        total(shopA,"corrected","4",0,1);
        detailSettlement("corrected",shopA,"corrected",OCTOBER_1,"TIKTOK_SHOP","ORDER","SETTLED","API","PHP",Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-30"));
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES('org',?,'corrected','corrected','2026-10-01','USER_CONFIRMED_MONTH','test','u')",shopA);
        total("UNASSIGNED","unassigned","19",0,1);settlement("unassigned","UNASSIGNED","unassigned",LocalDate.of(2026,10,14));
        total(ungrouped,"ungrouped","20",0,1);settlement("ungrouped",ungrouped,"ungrouped",LocalDate.of(2026,10,14));
        String foreign=groupedShop(other,"semantic-foreign",null);total(other,foreign,"foreign","16",0,1);
        insertSettlement("foreign","other-org",foreign,"foreign","SHOPEE","settlement","SETTLED","API",LocalDate.of(2026,10,15),"PHP");
        initializeAndPublish();return new SemanticFixture(shopA,groupA,groupB);
    }
    void detailSettlement(String id,String shop,String order,LocalDate day,String platform,String category,String status,String source,String currency,Map<String,Object> raw) {
        insertSettlement(id,"org",shop,order,platform,category,status,source,day,currency);
        db.update("UPDATE finance_records SET statement_id=?,raw_json=? WHERE id=?",id,repo.write(raw),id);
    }
    Filter selected(LocalDate start,LocalDate end,String shop,String platform,String currency,String group) {return new Filter(start,end,shop,platform,currency,"day",group);}
    record Expected(Filter filter,long orders,String amount,long conflicts,long shipments) {}
    record SemanticFixture(String shopA,String groupA,String groupB) {}

    void assertNativeComplete() {
        var source=new ObservedSource(dataSource);var page=labels(source,configuration(Mode.ACTIVE,4,grants(Mode.ACTIVE)),System::nanoTime).workspace(owner,october,Map.of());
        assertPeriod(period(page),1,"2",0,1);assertPeriodEquals(nativePeriod(october),period(page));assertPeriodEquals(oracle(october),period(page));
        assertEquals(1,source.count(ObservedSource::nativePeriod));assertEquals(0,source.count(ObservedSource::factAggregate));
    }

    Resolution resolve(int cap,Filter filter) {
        var grant=new ApprovedRange("org",OCTOBER_1,OCTOBER_31,Mode.ACTIVE,1,1,"synthetic-disposable-proof");
        var reader=new FinanceLabelFirstSettlementFacts(repo,cap,List.of(grant),BUDGET);
        return repo.readSnapshot(30,()->reader.period(Mode.ACTIVE,owner,filter));
    }

    void total(String shop,String order,String amount,int conflicts,int shipments) {
        total(owner,shop,order,amount,conflicts,shipments);
    }
    void total(Scope scope,String shop,String order,String amount,int conflicts,int shipments) {
        db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES(?,'active') ON DUPLICATE KEY UPDATE active_generation='active'",scope.organizationId());
        db.update("""
            INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
            VALUES(?,'active',?,?,?,?,?)
            """,scope.organizationId(),shop,order,decimal(amount),conflicts,shipments);
    }

    void settlement(String id,String shop,String order,LocalDate date) {
        insertSettlement(id,"org",shop,order,"SHOPEE","settlement","SETTLED","API",date,"PHP");
    }

    void initializeAndPublish() {
        initializeAndPublish("org");
    }
    void initializeAndPublish(String organization) {
        var init=new FinanceFirstSettlementInitialization(repo);
        init.discoverCompanies(100);
        var facts=new FinanceFirstSettlementFacts(repo);
        var guard=facts.guard(organization).orElseThrow();
        boolean seeded=false;
        for(int attempt=0;attempt<100;attempt++)if(init.seed(guard,100).complete()){seeded=true;break;}
        assertTrue(seeded,"Fixture candidate seed must finish");
        boolean requeued=false;
        for(int attempt=0;attempt<100;attempt++)if(init.requeue(guard,100).complete()){requeued=true;break;}
        assertTrue(requeued,"Fixture requeue must finish");
        assertTrue(init.complete(guard));
        for(int attempt=0;attempt<100;attempt++) {
            var lease=facts.claim(organization,UUID.randomUUID().toString(),30);
            if(lease.isEmpty()) {
                lease=facts.claim(organization,UUID.randomUUID().toString(),30);
                if(lease.isEmpty())return;
            }
            assertTrue(facts.publish(facts.calculate(lease.orElseThrow())));
        }
        fail("Fixture publication did not finish within its bounded owner count");
    }

    /** Uses the original anti-earlier identity predicate, independently of the new adapter and clean predicate. */
    Period oracle(Filter filter) {
        var q=repo.query(owner,filter,"r");
        var args=new ArrayList<Object>();args.add("org");args.addAll(q.args());
        var result=db.queryForMap("""
            SELECT COUNT(*) orders,COALESCE(SUM(lc.amount_cny),0) amount_cny,
              COALESCE(SUM(lc.pending_count),0) conflicts,COALESCE(SUM(lc.shipment_count),0) shipments
            FROM finance_label_state ls JOIN finance_label_order_totals lc
              ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
            JOIN finance_records r ON r.organization_id=lc.organization_id AND r.shop_id=lc.shop_id AND r.order_id=lc.order_id
            WHERE ls.organization_id=? AND
            """+FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+FinanceOrderCharges.normalOrder("r")+" AND "+q.where(),args.toArray());
        return new Period(decimal(result.get("amount_cny")),number(result.get("orders")),number(result.get("conflicts")),number(result.get("shipments")));
    }

    void assertPeriod(Period value,long orders,String amount,long conflicts,long shipments) {
        assertNotNull(value);assertEquals(orders,value.orders());
        assertEquals(0,decimal(amount).compareTo(value.amountCny()));
        assertEquals(conflicts,value.conflicts());assertEquals(shipments,value.shipments());
    }

    void assertPeriodEquals(Period expected,Period actual) {
        assertPeriod(actual,expected.orders(),expected.amountCny().toPlainString(),expected.conflicts(),expected.shipments());
    }

    List<ApprovedRange> grants(Mode mode) {return List.of(new ApprovedRange("org",OCTOBER_1,OCTOBER_31,mode,1,1,"synthetic-disposable-proof"));}
    FinanceLabelFirstSettlementFacts.Configuration configuration(Mode mode,int cap,List<ApprovedRange> grants) {
        return new FinanceLabelFirstSettlementFacts.Configuration(mode,cap,grants,BUDGET,"");
    }
    FinanceLabelCosts labels(ObservedSource source,FinanceLabelFirstSettlementFacts.Configuration configuration,LongSupplier clock) {
        return new FinanceLabelCosts(new FinanceRepository(new JdbcTemplate(source),new ObjectMapper()),null,
            Path.of("target","label-first-facts").toString(),configuration,clock);
    }
    Map<String,Object> springProperties(Mode mode) {
        var properties=new HashMap<String,Object>();properties.put("finance.first-settlement-facts.mode",mode.name());
        properties.put("finance.first-settlement-facts.allowed-ranges","[{\"organizationId\":\"org\",\"start\":\"2026-10-01\",\"end\":\"2026-10-31\",\"mode\":\""+mode.name()+"\",\"schemaVersion\":1,\"registrationVersion\":1,\"evidenceId\":\"synthetic-spring-binding\"}]");
        if(mode==Mode.OFF)properties.put("finance.first-settlement-facts.allowed-ranges","[]");
        properties.put("finance.label-import-directory","target/first-facts-spring-binding");
        properties.put("finance.first-settlement-facts.dirty-cap","4");properties.put("finance.first-settlement-facts.candidate-millis","10000");
        properties.put("finance.first-settlement-facts.native-reserve-millis","10000");properties.put("finance.first-settlement-facts.completion-reserve-millis","2000");return properties;
    }
    BoundLabels bindLabels(ObservedSource source,Map<String,Object> properties) {
        // Exercise Spring's real @Autowired/@Value resolution and conversion. The lifecycle
        // scheduler is deliberately absent: no CommonAnnotationBeanPostProcessor is installed.
        var factory=new DefaultListableBeanFactory();
        factory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
        var sources=new MutablePropertySources();sources.addFirst(new MapPropertySource("disposable-binding",properties));
        var resolver=new PropertySourcesPropertyResolver(sources);factory.addEmbeddedValueResolver(resolver::resolveRequiredPlaceholders);
        var processor=new AutowiredAnnotationBeanPostProcessor();processor.setBeanFactory(factory);factory.addBeanPostProcessor(processor);
        factory.registerSingleton("financeRepository",new FinanceRepository(new JdbcTemplate(source),new ObjectMapper()));factory.registerSingleton("financeService",service);
        factory.registerBeanDefinition("financeLabelCosts",new RootBeanDefinition(FinanceLabelCosts.class));
        try{return new BoundLabels(factory.getBean(FinanceLabelCosts.class),factory);}
        catch(RuntimeException failure){factory.destroySingletons();throw failure;}
    }
    record BoundLabels(FinanceLabelCosts labels,DefaultListableBeanFactory factory) implements AutoCloseable {
        @Override public void close(){labels.stop();factory.destroySingletons();}
    }
    Period period(Map<String,Object> page) {
        var values=map(page.get("period"));return new Period(decimal(values.get("amount_cny")),number(values.get("orders")),number(values.get("conflicts")),number(values.get("shipments")));
    }
    Period nativePeriod(Filter filter) {
        var values=repo.readSnapshot(30,()->new FinanceLabelCosts(repo,null,"target/label-first-facts-native").nativePeriod(owner,filter));
        return new Period(decimal(values.get("amount_cny")),number(values.get("orders")),number(values.get("conflicts")),number(values.get("shipments")));
    }
    void assertNoNewTables(ObservedSource source) {
        assertFalse(source.prepared.stream().anyMatch(sql->sql.contains("finance_first_settlement_")),"OFF and ungranted requests cannot touch prototype tables");
        assertEquals(1,source.count(ObservedSource::nativePeriod));
    }
    void sourceFile(String shop,String order) {
        db.update("UPDATE finance_label_state SET requested_revision=1,applied_revision=1 WHERE organization_id='org'");
        db.update("""
            INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,row_count,matched_count,amount_cny,created_by)
            VALUES('old-file','org','old.xlsx',?,'',0,'READY',1,1,2,'u')
            ""","a".repeat(64));
        db.update("""
            INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,
              shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note,match_checked)
            VALUES('old-file',1,'org','event-1','row-1','bucket-1',?,'shop','customer','warehouse','doc','2026-08-01',2,?,'MATCHED','old-note',TRUE)
            """,order,shop);
    }

    record Executed(String sql,long connectionId,int timeout,long timeoutMillis) {}
    static final class ObservedSource extends AbstractDataSource {
        final DataSource delegate;
        final List<String> prepared=new ArrayList<>();
        final List<List<Object>> bindings=new ArrayList<>();
        final List<Executed> executed=new ArrayList<>();
        final CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);
        Predicate<String> pauseAfter=sql->false,pauseBefore=sql->false;
        Consumer<String> afterQuery=sql->{};
        Consumer<String> afterPrepare=sql->{};
        UnaryOperator<String> sqlTransform=UnaryOperator.identity();
        SqlAction beforeQuery=sql->{};
        boolean hideMillisecondSupport;
        Connection boundConnection;ConnectionHolder boundHolder;
        long deadline=-1;boolean oneHolder=true,oneDeadline=true,flags=true;
        int connections,rollbacks;
        ObservedSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
        private Connection wrap(Connection connection)throws SQLException {
            connections++;
            long id;
            try(var statement=connection.createStatement();var row=statement.executeQuery("SELECT CONNECTION_ID()")){assertTrue(row.next());id=row.getLong(1);}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("rollback"))rollbacks++;
                if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql) {
                    sql=sqlTransform.apply(sql);
                    var actualArgs=args.clone();actualArgs[0]=sql;
                    final String executedSql=sql;
                    capture((Connection)proxy);
                    prepared.add(sql);
                    if(pauseBefore.test(sql)){pauseBefore=ignored->false;pause();}
                    var statement=(PreparedStatement)invoke(connection,method,actualArgs);
                    afterPrepare.accept(sql);
                    var values=new TreeMap<Integer,Object>();
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(statementProxy,statementMethod,statementArgs)->{
                        String name=statementMethod.getName();
                        if(hideMillisecondSupport&&name.equals("isWrapperFor")&&statementArgs[0] instanceof Class<?> type&&type.getName().equals("com.mysql.cj.jdbc.JdbcStatement"))return false;
                        if((name.startsWith("set")&&statementArgs!=null&&statementArgs.length>1&&statementArgs[0] instanceof Integer index))values.put(index,statementArgs[1]);
                        if(name.equals("executeQuery")) {
                            beforeQuery.run(executedSql);
                            executed.add(new Executed(executedSql,id,statement.getQueryTimeout(),driverMillis(statement)));bindings.add(new ArrayList<>(values.values()));
                            Object result=invoke(statement,statementMethod,statementArgs);
                            if(pauseAfter.test(executedSql)){pauseAfter=ignored->false;pause();}
                            afterQuery.accept(executedSql);return result;
                        }
                        return invoke(statement,statementMethod,statementArgs);
                    });
                }
                return invoke(connection,method,args);
            });
        }
        private void capture(Connection connection)throws SQLException {
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(this);
            flags&=TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                &&connection.isReadOnly()&&!connection.getAutoCommit()&&connection.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ
                &&holder!=null&&holder.hasTimeout()&&holder.getConnection()==connection;
            if(boundHolder==null){boundHolder=holder;boundConnection=connection;deadline=holder.getDeadline().getTime();}
            else {oneHolder&=boundHolder==holder&&boundConnection==connection;oneDeadline&=deadline==holder.getDeadline().getTime();}
        }
        private void pause() {
            reached.countDown();
            try {assertTrue(release.await(10,TimeUnit.SECONDS),"Test writer did not release the actual workspace statement");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        }
        void assertSnapshot() {
            assertTrue(flags);assertTrue(oneHolder);assertTrue(oneDeadline);assertEquals(1,connections);
            assertEquals(1,executed.stream().map(Executed::connectionId).distinct().count());
        }
        long count(Predicate<String> predicate){return executed.stream().filter(row->predicate.test(row.sql())).count();}
        static boolean candidate(String sql){return sql.contains("finance_first_settlement_")||lookup(sql);}
        static boolean factAggregate(String sql){return sql.contains("f.first_record_id id")&&sql.contains("SUM(lc.amount_cny)");}
        static boolean nativePeriod(String sql){return sql.contains("JOIN LATERAL (")&&sql.contains("SUM(lc.amount_cny)");}
        static boolean lookup(String sql){return sql.contains("SELECT r0.id,r0.organization_id,r0.shop_id,r0.order_id,r0.record_date");}
        static Object invoke(Object receiver,java.lang.reflect.Method method,Object[] args)throws Throwable {
            try{return method.invoke(receiver,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        }
        @SuppressWarnings({"rawtypes","unchecked"})
        private static long driverMillis(PreparedStatement statement)throws SQLException {
            try {
                Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");Object mysql=statement.unwrap(type);
                Object query=type.getMethod("getQuery").invoke(mysql);
                return ((Number)Class.forName("com.mysql.cj.Query").getMethod("getTimeoutInMillis").invoke(query)).longValue();
            }catch(ReflectiveOperationException failure){throw new SQLException("Cannot inspect the disposable driver's effective timeout",failure);}
        }
    }
    @FunctionalInterface interface SqlAction {void run(String sql)throws SQLException;}
}

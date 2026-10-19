package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommerceImageOssMaintenanceTest {
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceDesignImageGateway gateway=mock(CommerceDesignImageGateway.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final String key="a".repeat(64)+"/"+"b".repeat(64);
    void ready(){
        when(archive.usesOss()).thenReturn(true);
        when(db.queryForList(contains("status='READY'"))).thenReturn(List.of(Map.of(
                "id","ready-operation","organization_id","org","storage_key",key,"attempt_count",0)));
    }
    @Test void releaseHappensOnlyAfterVerifiedOssReadAndBeforeDatabasePayloadCleanup()throws Exception {
        ready();
        var maintenance=new CommerceImageOssMaintenance(archive,gateway,db);
        try{maintenance.migrate();}finally{maintenance.close();}
        var order=inOrder(archive,gateway,db);
        order.verify(archive).usesOss();
        order.verify(db).queryForList(contains("UNION SELECT"));
        order.verify(archive).migrateLocal(25);
        order.verify(db).queryForList(contains("status='READY'"));
        order.verify(archive).read("org",key);
        order.verify(gateway).releaseArchivedOperation("ready-operation",key);
        order.verify(db).update(contains("SET result_json=NULL"),eq("ready-operation"),eq("org"),eq(key),eq(0));
    }
    @Test void failedArchiveVerificationKeepsBothPaidResponseCopies()throws Exception {
        ready();when(archive.read("org",key)).thenThrow(new IllegalStateException("missing"));
        var maintenance=new CommerceImageOssMaintenance(archive,gateway,db);
        try{assertThrows(IllegalStateException.class,maintenance::migrate);}finally{maintenance.close();}
        verifyNoInteractions(gateway);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void runtimeCleanupFailureRetainsDatabaseResponseForRetry()throws Exception {
        ready();doThrow(new IllegalStateException("runtime down")).when(gateway).releaseArchivedOperation("ready-operation",key);
        var maintenance=new CommerceImageOssMaintenance(archive,gateway,db);
        try{assertThrows(IllegalStateException.class,maintenance::migrate);}finally{maintenance.close();}
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void localDevelopmentDoesNotRetireItsOnlyRecoveryCopy()throws Exception {
        var maintenance=new CommerceImageOssMaintenance(archive,gateway,db);
        try{maintenance.migrate();}finally{maintenance.close();}
        verifyNoInteractions(gateway,db);verify(archive,never()).migrateLocal(anyInt());
    }
}

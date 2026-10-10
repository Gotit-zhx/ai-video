package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommerceBatchModelLimitServiceTest {
 final JdbcTemplate db=mock(JdbcTemplate.class);final CommerceBatchModelLimitService service=new CommerceBatchModelLimitService(db);
 void limit(int max,int used){when(db.queryForList("SELECT * FROM commerce_batch_model_limits WHERE execution_id=? AND organization_id=? FOR UPDATE","run","org")).thenReturn(List.of(Map.of("max_calls",max,"reserved_calls",used)));}
 @Test void zeroOrMissingBudgetDoesNotReserve(){assertFalse(service.reserve("run","org","request-123","EXTRACT"));limit(0,0);assertFalse(service.reserve("run","org","request-123","EXTRACT"));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void firstRequestReservesOnce(){limit(2,0);assertTrue(service.reserve("run","org","request-123","EXTRACT"));verify(db).update("UPDATE commerce_batch_model_limits SET reserved_calls=reserved_calls+1 WHERE execution_id=? AND organization_id=?","run","org");}
 @Test void replayDoesNotIncrementAgain(){limit(1,1);when(db.queryForList("SELECT stage FROM commerce_batch_model_reservations WHERE execution_id=? AND request_key=?","run","request-123")).thenReturn(List.of(Map.of("stage","EXTRACT")));assertTrue(service.reserve("run","org","request-123","EXTRACT"));verify(db,never()).update(anyString(),any(Object[].class));assertThrows(RuntimeException.class,()->service.reserve("run","org","request-123","MOCKUP"));}
 @Test void overLimitDoesNotReserve(){limit(2,2);assertFalse(service.reserve("run","org","request-123","EXTRACT"));}
 @Test void validatesCaps(){assertThrows(RuntimeException.class,()->CommerceBatchModelLimitService.validate(-1));assertThrows(RuntimeException.class,()->CommerceBatchModelLimitService.validate(1001));}
}

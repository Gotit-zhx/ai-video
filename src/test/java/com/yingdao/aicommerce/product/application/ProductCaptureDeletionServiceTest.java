package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductCaptureDeletionServiceTest {
 @Test void removesOnlyRequestedTenantCapture(){
  var db=mock(JdbcTemplate.class);var service=new ProductCaptureDeletionService(db);
  when(db.queryForList(contains("data_module=?"),eq("capture"),eq("org"),eq("CAPTURE"))).thenReturn(List.of(Map.of("status","FAILED")));
  when(db.queryForObject(anyString(),eq(Integer.class),eq("capture"),eq("org"),eq("capture"),eq("org"))).thenReturn(0);
  service.delete("capture","org");
  verify(db).update(contains("deleted_at=NOW(6)"),eq("capture"),eq("org"),eq("CAPTURE"));
  verify(db,never()).update(startsWith("DELETE"),any(Object[].class));
 }
 @Test void activeTaskBlocksRemoval(){
  var db=mock(JdbcTemplate.class);
  when(db.queryForList(anyString(),eq("capture"),eq("org"),eq("CAPTURE"))).thenReturn(List.of(Map.of("status","READY")));
  when(db.queryForObject(anyString(),eq(Integer.class),eq("capture"),eq("org"),eq("capture"),eq("org"))).thenReturn(1);
  assertEquals(HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->new ProductCaptureDeletionService(db).delete("capture","org")).getStatus());
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void missingOrOtherTenantIsNotFound(){
  var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq("capture"),eq("other-org"),eq("CAPTURE"))).thenReturn(List.of());
  assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->new ProductCaptureDeletionService(db).delete("capture","other-org")).getStatus());
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void repeatedDeletionIsIdempotent(){
  var db=mock(JdbcTemplate.class);when(db.queryForList(anyString(),eq("capture"),eq("org"),eq("CAPTURE"))).thenReturn(List.of(Map.of("status","READY","deleted_at","already-deleted")));
  new ProductCaptureDeletionService(db).delete("capture","org");verify(db,never()).update(anyString(),any(Object[].class));
 }
}

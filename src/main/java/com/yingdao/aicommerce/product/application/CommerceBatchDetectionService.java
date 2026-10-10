package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Real source-data checks. Never represents a visual AI or copyright verdict. */
@Service
public class CommerceBatchDetectionService {
 public record Item(String captureId,long version,String title,int imageCount,int skuCount,List<String> issues){}
 public record Report(String batchId,Instant checkedAt,List<Item> items,boolean visualDetectionAvailable){}
 private final CommerceBatchService batches;private final ProductCaptureService captures;
 public CommerceBatchDetectionService(CommerceBatchService batches,ProductCaptureService captures){this.batches=batches;this.captures=captures;}
 public Report check(String id,PlatformPrincipal user){
  var batch=batches.get(id,user);var checked=new LinkedHashMap<String,Item>();
  for(var item:batch.items()){
   if(checked.containsKey(item.captureId()))continue;
   var detail=captures.get(item.captureId(),user);var issues=new ArrayList<String>();
   if(detail.capture().getVersion()!=item.captureVersion())issues.add("来源版本已变化，需重新固定批次来源");
   issues.addAll(captures.approvalMissingFields(detail.capture(),detail.skus(),detail.images()));
   checked.put(item.captureId(),new Item(item.captureId(),item.captureVersion(),detail.capture().getTitle(),detail.images().size(),detail.skus().size(),List.copyOf(issues)));
  }
  return new Report(id,Instant.now(),List.copyOf(checked.values()),false);
 }
}

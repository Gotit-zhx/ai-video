package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.mapper.*;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

@Component
public class ProductWatermarkQueueWorker {
 private static final Logger log=LoggerFactory.getLogger(ProductWatermarkQueueWorker.class);
 private final ProductWatermarkQueue queue;
 private final ProductCaptureMapper captures;
 private final ProductCaptureImageMapper images;
 private final AiWatermarkRemovalProvider provider;
 private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
 private final ConcurrentMap<String,ProductWatermarkQueue.Work> active=new ConcurrentHashMap<>();
 public ProductWatermarkQueueWorker(ProductWatermarkQueue queue,ProductCaptureMapper captures,ProductCaptureImageMapper images,AiWatermarkRemovalProvider provider){this.queue=queue;this.captures=captures;this.images=images;this.provider=provider;}
 @Scheduled(initialDelay=5000,fixedDelay=1000)
 public void dispatch(){
  if(executor.isShutdown())return;
  queue.recoverUnqueuedJobs();
  for(var work:queue.claim()){
   active.put(work.id(),work);
   try{executor.execute(()->run(work));}
   catch(RejectedExecutionException e){active.remove(work.id());queue.release(work);}
  }
 }
 void run(ProductWatermarkQueue.Work work){
  String url=null,failure=null;Map<String,Object> result=null;
  try{
   var capture=captures.selectById(work.captureId());var image=images.selectById(work.imageId());
   if(capture==null||image==null||!work.organizationId().equals(capture.getOrganizationId())||!work.organizationId().equals(image.getOrganizationId())||!work.captureId().equals(image.getCaptureId()))throw new IllegalStateException("商品或来源图片已变化");
   var processed=provider.process(capture,image);
   if(processed.url()==null||!processed.url().matches("/api/v1/product-watermark/assets/[a-f0-9]{64}"))throw new IllegalStateException("图片处理未返回已验证归档");
   url=processed.url();result=processed.providerResponse();
  }catch(GeneratedImageDownloadPendingException e){
   try{queue.deferDownload(work,e.getMessage());}finally{active.remove(work.id());}return;
  }catch(WatermarkRateLimitException e){
   try{queue.defer(work,e.getMessage());}finally{active.remove(work.id());}return;
  }catch(Exception e){
   failure=Objects.toString(e.getMessage(),"图片处理失败");
   Throwable cause=e;for(int depth=0;depth<12&&cause.getCause()!=null&&cause.getCause()!=cause;depth++)cause=cause.getCause();
   // Record classification without serializing signed image URLs, response bodies or model keys.
   log.warn("Watermark image {} failed: errorType={}, causeType={}",work.imageId(),e.getClass().getSimpleName(),cause.getClass().getSimpleName());
   try{queue.deferFailure(work,failure);}finally{active.remove(work.id(),work);}return;
  }
  try{queue.finish(work,url,result,failure);}
  catch(RuntimeException e){log.error("Could not commit watermark queue result {}",work.id(),e);}
  finally{active.remove(work.id());}
 }
 @Scheduled(initialDelay=30000,fixedDelay=30000)
 public void maintain(){
  for(var work:active.values())queue.heartbeat(work);
  for(var work:queue.expired())queue.finishExpired(work);
 }
 @PreDestroy public void close(){executor.shutdown();}
}

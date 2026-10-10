package com.yingdao.aicommerce.product.application;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class ProductWatermarkAssets {
 public static final String PREFIX="/api/v1/product-watermark/assets/";
 private final ProductWatermarkLedger ledger;private final CommerceImageArchive archive;
 public ProductWatermarkAssets(ProductWatermarkLedger ledger,CommerceImageArchive archive){this.ledger=ledger;this.archive=archive;}
 public Map<String,Object> record(String org,String id){return ledger.get(id,org);}
 public CommerceDesignImageGateway.Reference read(String org,String capture,String image,String id){
  var row=ledger.get(id,org);
  if(!Objects.equals(capture,row.get("capture_id"))||!Objects.equals(image,row.get("image_id"))||!"READY".equals(row.get("status")))throw new IllegalArgumentException("图片尚未通过水印及商品保真检查");
  return archive.read(org,(String)row.get("output_key"));
 }
}

package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.storage.OssStorageService;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/** Explicit release-only smoke entry point. Never discovered as a JUnit test or application bean. */
public class CommerceImageOssLiveCheck {
    private static String env(String name,String fallback){return System.getenv().getOrDefault(name,fallback);}
    public static void main(String[] args)throws Exception{
        var storage=new OssStorageService(env("APP_OSS_BUCKET",""),env("APP_OSS_REGION","cn-guangzhou"),
                env("APP_OSS_ENDPOINT","https://oss-cn-guangzhou-internal.aliyuncs.com"),
                env("APP_OSS_PUBLIC_ENDPOINT","https://oss-cn-guangzhou.aliyuncs.com"),env("ALIBABA_CLOUD_ECS_METADATA",""),
                Duration.ofHours(2),Boolean.parseBoolean(env("APP_OSS_PUBLIC_IMAGES","false")),null,
                env("APP_OSS_CREDENTIALS_FILE",""),env("APP_DAYAN_OSS_BUCKET",""),env("APP_DAYAN_OSS_CREDENTIALS_FILE",""));
        if(args.length>0){
            try{
                if(args.length<3||!"--read-existing".equals(args[0]))
                    throw new IllegalArgumentException("Expected --read-existing organization archive-key...");
                String tenant=CommerceImageArchive.sha(args[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                long total=0;
                for(int index=2;index<args.length;index++){
                    String key=args[index];
                    if(!key.matches("[a-f0-9]{64}/[a-f0-9]{64}")||!key.startsWith(tenant+"/"))
                        throw new IllegalArgumentException("Wrong tenant archive key");
                    String location=storage.commerceImageLocation(key);
                    var url=java.net.URI.create(storage.signedUrl(location));
                    if(!"oss.cloud.dayan.cloud".equalsIgnoreCase(url.getHost()))
                        throw new IllegalStateException("Existing image is not routed to Dayan OSS");
                    byte[] bytes=storage.readAllBytes(location,8*1024*1024);
                    if(!CommerceImageArchive.sha(bytes).equals(key.substring(65)))
                        throw new IllegalStateException("Existing image digest differs");
                    if(ImageIO.read(new java.io.ByteArrayInputStream(bytes))==null)
                        throw new IllegalStateException("Existing object is not a readable image");
                    total+=bytes.length;
                }
                System.out.println("{\"dayanReadback\":true,\"images\":"+(args.length-2)
                        +",\"sha256Verified\":true,\"decodableImages\":true,\"bytes\":"+total+",\"writes\":0}");
            }finally{storage.close();}
            return;
        }
        var root=Files.createTempDirectory("commerce-oss-check");
        var archive=new CommerceImageArchive(root.toString(),storage);
        var org="release-check-"+UUID.randomUUID();
        var locations=new ArrayList<String>();
        try{
            var out=new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB),"png",out);
            byte[] bytes=out.toByteArray();
            var saved=archive.store(org,bytes);locations.add(storage.commerceImageLocation(saved.storageKey()));
            if(Files.exists(root.resolve(saved.storageKey())))throw new IllegalStateException("New image was written locally");
            if(!Arrays.equals(bytes,Base64.getDecoder().decode(archive.read(org,saved.storageKey()).dataBase64())))
                throw new IllegalStateException("OSS readback differs");
            var legacy=new CommerceImageArchive(root.toString()).store(org+"-legacy",bytes);
            locations.add(storage.commerceImageLocation(legacy.storageKey()));
            archive.migrateKnown(org+"-legacy",legacy.storageKey());
            if(Files.exists(root.resolve(legacy.storageKey())))throw new IllegalStateException("Legacy image not migrated");
            if(!Arrays.equals(bytes,Base64.getDecoder().decode(archive.read(org+"-legacy",legacy.storageKey()).dataBase64())))
                throw new IllegalStateException("Migrated image differs");
            boolean tenantRejected=false;
            try{archive.read(org+"-other",saved.storageKey());}catch(IllegalArgumentException expected){tenantRejected=true;}
            if(!tenantRejected)throw new IllegalStateException("Tenant isolation failed");
            System.out.println("{\"directOssUpload\":true,\"sha256Readback\":true,\"legacyMigration\":true,\"localImageFiles\":0,\"tenantIsolation\":true}");
        }finally{
            for(String location:locations)storage.delete(location);
            storage.close();
            try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}

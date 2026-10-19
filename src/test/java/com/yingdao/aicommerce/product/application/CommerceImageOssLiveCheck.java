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

package com.yingdao.aicommerce.product.application;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.yingdao.aicommerce.storage.OssStorageService;
import java.nio.file.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Tenant-owned content addresses stay stable while OSS owns the durable image bytes. */
@Service
public class CommerceImageArchive {
    private static final int LIMIT=8*1024*1024;
    public record Archived(String storageKey,String sha256,String mimeType,int byteLength) {}
    private final Path root;
    private final OssStorageService oss;
    @Autowired
    public CommerceImageArchive(@Value("${app.commerce-image-storage:${app.ai-video-task-storage:/data/ai-video-tasks}/commerce-images}")String root,
                                ObjectProvider<OssStorageService> storage){this(root,storage.getIfAvailable());}
    public CommerceImageArchive(String root){this(root,(OssStorageService)null);}
    CommerceImageArchive(String root,OssStorageService oss){this.root=Path.of(root).toAbsolutePath().normalize();this.oss=oss;}
    public boolean usesOss(){return oss!=null;}
    public Archived store(String org,byte[] bytes){
        String mime=detect(bytes),hash=sha(bytes),tenant=tenant(org);String key=tenant+"/"+hash;
        if(oss!=null){
            ensureRemote(key,bytes,mime);
            return new Archived(key,hash,mime,bytes.length);
        }
        try{
            Path directory=directory(tenant);Path file=directory.resolve(hash);
            try{Files.write(file,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);}
            catch(FileAlreadyExistsException e){read(org,key);}
            return new Archived(key,hash,mime,bytes.length);
        }catch(IOException e){throw new IllegalStateException("图片归档失败",e);}
    }
    public CommerceDesignImageGateway.Reference read(String org,String key){
        if(key==null||!key.matches("[a-f0-9]{64}/[a-f0-9]{64}")||!key.startsWith(tenant(org)+"/"))throw new IllegalArgumentException("图片归档不属于当前公司");
        if(oss!=null){
            try{
                byte[] bytes;
                if(oss.exists(oss.commerceImageLocation(key))){
                    bytes=remoteBytes(key);
                    if(Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS))removeLocal(key);
                }
                else{
                    bytes=localBytes(key);
                    ensureRemote(key,bytes,detect(bytes));
                    removeLocal(key);
                }
                return new CommerceDesignImageGateway.Reference(detect(bytes),Base64.getEncoder().encodeToString(bytes));
            }catch(Exception e){throw new IllegalStateException("读取OSS图片归档失败，未重新生成图片",e);}
        }
        try{
            byte[] bytes=localBytes(key);
            return new CommerceDesignImageGateway.Reference(detect(bytes),Base64.getEncoder().encodeToString(bytes));
        }catch(IOException e){throw new IllegalStateException("读取图片归档失败",e);}
    }
    private byte[] localBytes(String key)throws IOException{
        Path file=directory(key.substring(0,64)).resolve(key.substring(65));
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>LIMIT)throw new IllegalStateException("归档文件不可用");
        byte[] bytes;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(LIMIT+1);}
        validateBytes(key,bytes);
        return bytes;
    }
    private static void validateBytes(String key,byte[] bytes){
        detect(bytes);
        if(!sha(bytes).equals(key.substring(65)))throw new IllegalStateException("归档图片内容已变化，拒绝生成");
    }
    private byte[] remoteBytes(String key)throws Exception{
        byte[] bytes=oss.readAllBytes(oss.commerceImageLocation(key),LIMIT);
        validateBytes(key,bytes);
        return bytes;
    }
    private void ensureRemote(String key,byte[] bytes,String mime){
        try{
            if(!oss.exists(oss.commerceImageLocation(key)))oss.storeCommerceImage(key,bytes,mime);
            if(!Arrays.equals(bytes,remoteBytes(key)))throw new IllegalStateException("OSS图片校验不一致");
        }catch(Exception e){throw new IllegalStateException("OSS图片归档失败，未回退到服务器存储",e);}
    }
    private void removeLocal(String key)throws IOException{
        // Revalidate the local bytes too; never remove a changed file or a symbolic link.
        Path file=root.resolve(key);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
        try{localBytes(key);}
        catch(IOException|IllegalStateException failure){
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            throw failure;
        }
        Files.deleteIfExists(file);
    }
    void migrateKnown(String org,String key){
        if(oss==null||key==null||!key.matches("[a-f0-9]{64}/[a-f0-9]{64}")||!key.startsWith(tenant(org)+"/"))return;
        if(Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS))read(org,key);
    }
    int migrateLocal(int maximum)throws IOException{
        if(oss==null||maximum<=0||!Files.exists(root))return 0;
        if(Files.isSymbolicLink(root))throw new IllegalStateException("归档根目录不能为软链接");
        int migrated=0;
        try(var tenants=Files.newDirectoryStream(root)){
            for(Path tenant:tenants){
                if(!tenant.getFileName().toString().matches("[a-f0-9]{64}")||Files.isSymbolicLink(tenant)
                        ||!Files.isDirectory(tenant,LinkOption.NOFOLLOW_LINKS))continue;
                try(var files=Files.newDirectoryStream(tenant)){
                    for(Path file:files){
                        if(!file.getFileName().toString().matches("[a-f0-9]{64}")||Files.isSymbolicLink(file)
                                ||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                        String key=tenant.getFileName()+"/"+file.getFileName();
                        byte[] bytes=localBytes(key);
                        ensureRemote(key,bytes,detect(bytes));
                        removeLocal(key);
                        if(++migrated>=maximum)return migrated;
                    }
                }
            }
        }
        return migrated;
    }
    private Path directory(String tenant)throws IOException{
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root))throw new IllegalStateException("归档根目录不能为软链接");
        Path realRoot=root.toRealPath(),dir=realRoot.resolve(tenant);Files.createDirectories(dir);
        if(Files.isSymbolicLink(dir)||!dir.toRealPath().getParent().equals(realRoot))throw new IllegalStateException("非法归档目录");return dir;
    }
    private static String tenant(String org){if(org==null||org.isBlank())throw new IllegalArgumentException("缺少公司");return sha(org.getBytes(StandardCharsets.UTF_8));}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String detect(byte[] b){
        if(b==null||b.length<12||b.length>LIMIT)throw new IllegalArgumentException("图片大小须为12字节至8MiB");
        if(b[0]==(byte)0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10)return "image/png";
        if(b[0]==(byte)0xff&&b[1]==(byte)0xd8&&b[2]==(byte)0xff)return "image/jpeg";
        if(b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P')return "image/webp";
        throw new IllegalArgumentException("仅支持PNG/JPEG/WEBP图片内容，不能按扩展名或请求MIME伪装");
    }
}

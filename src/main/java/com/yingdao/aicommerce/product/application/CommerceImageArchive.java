package com.yingdao.aicommerce.product.application;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Content-addressed tenant-local storage; byte signatures are not visual quality approval. */
@Service
public class CommerceImageArchive {
    private static final int LIMIT=8*1024*1024;
    public record Archived(String storageKey,String sha256,String mimeType,int byteLength) {}
    private final Path root;
    public CommerceImageArchive(@Value("${app.commerce-image-storage:${app.ai-video-task-storage:/data/ai-video-tasks}/commerce-images}")String root){this.root=Path.of(root).toAbsolutePath().normalize();}
    public Archived store(String org,byte[] bytes){
        String mime=detect(bytes),hash=sha(bytes),tenant=tenant(org);String key=tenant+"/"+hash;
        try{
            Path directory=directory(tenant);Path file=directory.resolve(hash);
            try{Files.write(file,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);}
            catch(FileAlreadyExistsException e){read(org,key);}
            return new Archived(key,hash,mime,bytes.length);
        }catch(IOException e){throw new IllegalStateException("图片归档失败",e);}
    }
    public CommerceDesignImageGateway.Reference read(String org,String key){
        if(key==null||!key.matches("[a-f0-9]{64}/[a-f0-9]{64}")||!key.startsWith(tenant(org)+"/"))throw new IllegalArgumentException("图片归档不属于当前公司");
        try{
            Path file=directory(tenant(org)).resolve(key.substring(65));
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>LIMIT)throw new IllegalStateException("归档文件不可用");
            byte[] bytes;
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(LIMIT+1);}
            String mime=detect(bytes);
            if(!sha(bytes).equals(key.substring(65)))throw new IllegalStateException("归档图片内容已变化，拒绝生成");
            return new CommerceDesignImageGateway.Reference(mime,Base64.getEncoder().encodeToString(bytes));
        }catch(IOException e){throw new IllegalStateException("读取图片归档失败",e);}
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

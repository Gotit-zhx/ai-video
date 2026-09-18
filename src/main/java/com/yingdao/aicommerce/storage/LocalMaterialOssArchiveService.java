package com.yingdao.aicommerce.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Keeps durable OSS copies while analysis retains its required local working files. */
@Service
@ConditionalOnProperty(name="app.material-archive.enabled", havingValue="true")
public class LocalMaterialOssArchiveService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LocalMaterialOssArchiveService.class);
    private static final Set<String> MEDIA=Set.of("png","jpg","jpeg","webp","gif","avif","bmp","heic","mp4","mov","webm","mkv","avi","m4v","mp3","wav","m4a","aac","ogg","flac","svg");
    private final OssStorageService storage;
    private final List<Path> roots;
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(task,"oss-material-archive");thread.setDaemon(true);return thread;
    });
    public LocalMaterialOssArchiveService(OssStorageService storage,
        @Value("${app.video-script-storage:/data/video-scripts}") String scripts,
        @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String tasks,
        @Value("${app.automation-attachment-storage:/data/automation-attachments}") String attachments) {
        this.storage=storage;
        this.roots=List.of(Path.of(scripts),Path.of(tasks),Path.of(attachments));
    }
    static boolean eligible(Path root,Path file) {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file))return false;
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(file.toString().contains("/_download-archives/")||file.toString().contains("/.oss-archive/"))return false;
        if(root.getFileName().toString().equals("ai-video-tasks")&&name.startsWith("video-")&&name.endsWith(".mp4"))return false;
        return root.getFileName().toString().equals("automation-attachments")||MEDIA.contains(name.substring(name.lastIndexOf('.')+1));
    }
    public String archivedUrl(Path source) {
        if(source==null)return null;
        source=source.toAbsolutePath().normalize();
        try {
            for(Path configured:roots) {
                Path root=configured.toAbsolutePath().normalize();
                if(!source.startsWith(root)||!eligible(root,source))continue;
                String relative=root.relativize(source).toString();
                String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(relative.getBytes(StandardCharsets.UTF_8)));
                Path marker=root.resolve(".oss-archive").resolve(id+".txt");
                if(!Files.isRegularFile(marker))return null;
                var lines=Files.readAllLines(marker);
                String fingerprint=Files.size(source)+":"+Files.getLastModifiedTime(source).toMillis();
                if(lines.size()!=2||!lines.get(0).equals(fingerprint)||!storage.isOssLocation(lines.get(1)))return null;
                return storage.signedUrl(lines.get(1));
            }
        } catch(Exception ignored) { }
        return null;
    }
    @Scheduled(initialDelay=90_000,fixedDelay=60_000)
    public void scheduleArchive() {
        if(!running.compareAndSet(false,true))return;
        try {worker.execute(() -> {try {archive();} finally {running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException stopped) {running.set(false);}
    }
    @jakarta.annotation.PreDestroy
    public void close() {worker.shutdownNow();}

    public void archive() {
        int remaining=50;
        for(Path root:roots) {
            if(!Files.isDirectory(root))continue;
            try(var files=Files.walk(root)) {
                var iterator=files.filter(path->eligible(root,path)).iterator();
                while(iterator.hasNext()&&remaining>0) {
                    Path source=iterator.next();
                    long size=Files.size(source),modified=Files.getLastModifiedTime(source).toMillis();
                    if(size==0||System.currentTimeMillis()-modified<60_000)continue;
                    String relative=root.relativize(source).toString();
                    String identity=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(relative.getBytes(StandardCharsets.UTF_8)));
                    Path marker=root.resolve(".oss-archive").resolve(identity+".txt");
                    String fingerprint=size+":"+modified;
                    if(Files.isRegularFile(marker)&&Files.readString(marker).startsWith(fingerprint+"\n"))continue;
                    remaining--;
                    try(var input=Files.newInputStream(source)) {
                        String mime=Files.probeContentType(source);
                        var stored=storage.store(input,size,500L*1024*1024,mime,"local-materials/"+root.getFileName()+"/"+identity,source.getFileName().toString());
                        if(!storage.exists(stored.location())||storage.download(stored.location()).size()!=size)throw new IllegalStateException("Archive verification failed");
                        if(Files.size(source)!=size||Files.getLastModifiedTime(source).toMillis()!=modified)continue;
                        Files.createDirectories(marker.getParent());
                        Path pending=Files.createTempFile(marker.getParent(),"marker-",".tmp");
                        try {Files.writeString(pending,fingerprint+"\n"+stored.location());Files.move(pending,marker,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                        finally {Files.deleteIfExists(pending);}
                    } catch(Exception failure) {
                        log.warn("Material archive deferred for {} ({})",source.getFileName(),failure.getClass().getSimpleName());
                        return; // A storage outage must not trigger a retry storm across every file.
                    }
                }
            } catch(Exception failure) {log.warn("Material archive scan deferred ({})",failure.getClass().getSimpleName());}
            if(remaining==0)return;
        }
    }
}

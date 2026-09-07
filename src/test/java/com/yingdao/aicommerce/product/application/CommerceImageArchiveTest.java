package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class CommerceImageArchiveTest {
    @TempDir Path dir;
    byte[] png()throws Exception{var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);return output.toByteArray();}
    @Test void contentAddressedRoundTripAndDedupe()throws Exception{var archive=new CommerceImageArchive(dir.toString());var bytes=png();var first=archive.store("org",bytes);assertEquals(first,archive.store("org",bytes));var read=archive.read("org",first.storageKey());assertEquals("image/png",read.mimeType());assertArrayEquals(bytes,Base64.getDecoder().decode(read.dataBase64()));}
    @Test void otherTenantCannotRead()throws Exception{var archive=new CommerceImageArchive(dir.toString());var first=archive.store("org",png());assertThrows(IllegalArgumentException.class,()->archive.read("foreign",first.storageKey()));}
    @Test void traversalRejected(){var archive=new CommerceImageArchive(dir.toString());assertThrows(IllegalArgumentException.class,()->archive.read("org","../etc/passwd"));}
    @Test void tamperedArchiveBlocksGeneration()throws Exception{var archive=new CommerceImageArchive(dir.toString());var original=png();var saved=archive.store("org",original);original[original.length-1]^=1;Files.write(dir.resolve(saved.storageKey()),original);assertThrows(IllegalStateException.class,()->archive.read("org",saved.storageKey()));}
    @Test void htmlCannotPretendToBeImage(){var archive=new CommerceImageArchive(dir.toString());assertThrows(IllegalArgumentException.class,()->archive.store("org","<html>error response</html>".getBytes()));}
    @Test void oversizedImageRejected(){var archive=new CommerceImageArchive(dir.toString());assertThrows(IllegalArgumentException.class,()->archive.store("org",new byte[8*1024*1024+1]));}
}

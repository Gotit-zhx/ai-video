import java.nio.file.*;
import java.util.List;
import com.yingdao.aicommerce.product.application.ProductWatermarkLocalEdit;
import com.yingdao.aicommerce.product.application.CommerceArtworkLocalEdit.Region;
/** Offline QA only; does not change application ledgers or authorize publication. */
class WatermarkRecompose {
 public static void main(String[] a)throws Exception{
  if(a.length!=7)throw new IllegalArgumentException("source candidate output x y width height");
  byte[] out=ProductWatermarkLocalEdit.preserve(Files.readAllBytes(Path.of(a[0])),Files.readAllBytes(Path.of(a[1])),List.of(new Region(Double.parseDouble(a[3]),Double.parseDouble(a[4]),Double.parseDouble(a[5]),Double.parseDouble(a[6]))));
  Files.write(Path.of(a[2]),out);
 }
}

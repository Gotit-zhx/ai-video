package com.yingdao.aicommerce.product.application.provider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yingdao.aicommerce.product.application.*;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
class ShopeeAttributeResolverTest {
    Map<String,Object> definition=Map.of("attribute_id",12,"name","Neckline","mandatory",true,
            "attribute_value_list",List.of(Map.of("value_id",34,"name","Round Neck")));
    Map<String,Object> answer=Map.of("attribute_id",12,"supported",true,"evidence","Title explicitly says Round Neck","value_ids",List.of(34));
    @Test void resolvesOfficialIdsWithEvidence() {
        assertThat(ShopeeAttributeResolver.merge(List.of(),List.of(definition),Map.of("attributes",List.of(answer))))
                .isEqualTo(List.of(Map.of("attribute_id",12,"attribute_value_list",List.of(Map.of("value_id",34,"original_value_name","Round Neck")))));
    }
    @Test void preservesExistingAndReplacesEmpty() {
        List<?> existing=List.of(Map.of("attribute_id",12,"attribute_value_list",List.of(Map.of("value_id",99))));
        assertThat(ShopeeAttributeResolver.merge(existing,List.of(definition),Map.of())).isEqualTo(existing);
        assertThat(ShopeeAttributeResolver.merge(List.of(Map.of("attribute_id",12,"attribute_value_list",List.of())),
                List.of(definition),Map.of("attributes",List.of(answer)))).hasSize(1);
    }
    @Test void refusesUnsupportedUnknownAndDuplicateAnswers() {
        assertThatThrownBy(()->ShopeeAttributeResolver.merge(List.of(),List.of(definition),Map.of())).hasMessageContaining("Neckline");
        for(var edit:List.of(Map.of("supported",false),Map.of("value_ids",List.of(999)),Map.of("evidence",""),Map.of("value_ids",List.of(34,34)))) {
            var invalid=new HashMap<String,Object>(answer);invalid.putAll(edit);
            assertThatThrownBy(()->ShopeeAttributeResolver.merge(List.of(),List.of(definition),Map.of("attributes",List.of(invalid))));
        }
        assertThatThrownBy(()->ShopeeAttributeResolver.merge(List.of(),List.of(definition),Map.of("attributes",List.of(answer,answer))));
    }

    @Test void fixedCommerceOriginalReadsAuthorizedArchiveInsteadOfPublicUrl() {
        var h=new Images();String url=CommercePublicationDrafts.PREFIX+"tenant/key";
        var image=h.image("org","capture",url+"?original",url);
        when(h.downloader.downloadProductImage(url,image)).thenReturn(h.binary);
        assertThat(h.resolver.originalImage(h.capture,image)).containsExactly(1,2,3);
        verify(h.downloader).downloadProductImage(url,image);verify(h.downloader,never()).download(anyString());
    }
    @Test void normalEditedImageStillUsesOriginalEvidence() {
        for(String edited:List.of("https://cdn.example/edited.png",CommercePublicationDrafts.PREFIX+"tenant/key")){
            var h=new Images();var image=h.image("org","capture","https://cdn.example/original.png",edited);
            when(h.downloader.download(image.getOriginalUrl())).thenReturn(h.binary);
            assertThat(h.resolver.originalImage(h.capture,image)).containsExactly(1,2,3);
            verify(h.downloader).download(image.getOriginalUrl());verify(h.downloader,never()).downloadProductImage(anyString(),any());
        }
    }
    @Test void originalWatermarkArchiveTakesPrecedenceOverCommerceFallback() {
        var h=new Images();String url=CommercePublicationDrafts.PREFIX+"tenant/key";var image=h.image("org","capture",url+"?original",url);
        when(h.db.queryForList(anyString(),eq("org"),eq("capture"),eq(image.getId()))).thenReturn(List.of(Map.of("source_key","source-key")));
        when(h.archive.read("org","source-key")).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(new byte[]{4,5,6})));
        assertThat(h.resolver.originalImage(h.capture,image)).containsExactly(4,5,6);verifyNoInteractions(h.downloader);
    }
    @Test void mismatchedCommerceAliasIsNotTreatedAsOriginal() {
        var h=new Images();String url=CommercePublicationDrafts.PREFIX+"tenant/key";var image=h.image("org","capture",url+"?original&other=true",url);
        when(h.downloader.download(image.getOriginalUrl())).thenThrow(new MarketplaceApiException("invalid URL"));
        assertThatThrownBy(()->h.resolver.originalImage(h.capture,image)).hasMessageContaining("invalid URL");
        verify(h.downloader,never()).downloadProductImage(anyString(),any());
    }
    @Test void imageOutsideCaptureOrTenantCannotReachArchiveOrNetwork() {
        for(String[] owner:List.of(new String[]{"other-org","capture"},new String[]{"org","other-capture"})){
            var h=new Images();var image=h.image(owner[0],owner[1],"https://cdn.example/original.png",null);
            assertThatThrownBy(()->h.resolver.originalImage(h.capture,image)).hasMessageContaining("归属不一致");
            verifyNoInteractions(h.archive,h.downloader,h.db);
        }
    }
    static class Images {
        final MarketplaceImageDownloader downloader=mock(MarketplaceImageDownloader.class);
        final CommerceImageArchive archive=mock(CommerceImageArchive.class);final JdbcTemplate db=mock(JdbcTemplate.class);
        final ProductCapture capture=mock(ProductCapture.class);
        final MarketplaceImageDownloader.ImageBinary binary=new MarketplaceImageDownloader.ImageBinary(new byte[]{1,2,3},"image/png",".png");
        final ShopeeAttributeResolver resolver=new ShopeeAttributeResolver(null,null,downloader,archive,db,new ObjectMapper(),"http://unused.invalid","unused",System.getProperty("java.io.tmpdir"));
        Images(){when(capture.getOrganizationId()).thenReturn("org");when(capture.getId()).thenReturn("capture");}
        ProductCaptureImage image(String org,String capture,String original,String processed){var image=new ProductCaptureImage(org,capture,original,"MAIN","",0,"image/png","{}");if(processed!=null)image.processed(processed);return image;}
    }
}

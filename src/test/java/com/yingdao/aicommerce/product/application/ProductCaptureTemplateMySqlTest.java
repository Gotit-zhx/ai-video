package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.*;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.product.web.ProductCaptureTemplateController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="CAPTURE_TEMPLATE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductCaptureTemplateMySqlTest {
    JdbcTemplate db;ProductCaptureTemplateService service;CommerceImageArchive archive;
    PlatformPrincipal owner=ProductCaptureTemplateServiceTest.user("qa-org","product:edit");
    ObjectMapper json=new ObjectMapper();byte[] png;
    @BeforeAll void setup()throws Exception{
        String url=System.getenv("CAPTURE_TEMPLATE_QA_JDBC");
        assertTrue(url.matches("jdbc:mysql://[^/]+/capture_template_qa_[a-f0-9]+\\?.*"),"Only a disposable capture_template_qa schema is permitted");
        var ds=new DriverManagerDataSource(url,System.getenv("CAPTURE_TEMPLATE_QA_USER"),System.getenv("CAPTURE_TEMPLATE_QA_PASSWORD"));
        db=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()){
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V160__product_capture_templates.sql"));
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V189__capture_template_drafts.sql"));
        }
        var buffer=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB),"png",buffer);png=buffer.toByteArray();
    }
    @BeforeEach void reset(){
        db.update("DELETE FROM product_capture_templates");db.update("DELETE FROM product_capture_template_images");
        archive=mock(CommerceImageArchive.class);when(archive.usesOss()).thenReturn(true);
        when(archive.store(anyString(),any())).thenAnswer(call->{String org=call.getArgument(0);byte[] bytes=call.getArgument(1);return new CommerceImageArchive.Archived(CommerceImageArchive.sha(org.getBytes())+"/"+CommerceImageArchive.sha(bytes),CommerceImageArchive.sha(bytes),"image/png",bytes.length);});
        when(archive.read(anyString(),anyString())).thenReturn(new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(png)));
        service=new ProductCaptureTemplateService(db,archive,json);
    }
    ProductCaptureTemplateService.Save value(long version,String name,String image){return new ProductCaptureTemplateService.Save(version,name,"",List.of(new ProductCaptureTemplateService.Variant(UUID.randomUUID().toString(),"正面",image,List.of(new ProductCaptureTemplateService.Color(UUID.randomUUID().toString(),"白色","#fff","001")),"A01")));}
    String upload(){return service.upload(new MockMultipartFile("file","layout.png","image/png",png),owner).id();}
    @Test void persistsAndReadsSingleStyleAndCodesWithTenantScopedImageReferences(){
        String image=upload(),id=UUID.randomUUID().toString();var input=value(0,"我的模板",image);
        var saved=service.save(id,input,owner);assertEquals(1,saved.version());assertEquals(1,saved.variants().size());
        var restored=service.get(id,owner).variants().getFirst();
        assertEquals("A01",restored.styleCode());assertEquals("001",restored.colors().getFirst().colorCode());
        assertEquals("#FFFFFF",service.get(id,owner).variants().getFirst().colors().getFirst().hex());
        assertEquals(saved,service.save(id,input,owner));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM product_capture_templates",Integer.class));
        assertArrayEquals(png,Base64.getDecoder().decode(service.image(image,owner).dataBase64()));
        var other=ProductCaptureTemplateServiceTest.user("other-org","product:edit");
        assertEquals(0,service.list(0,50,other).total());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.get(id,other)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.image(image,other)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.save(UUID.randomUUID().toString(),input,other)).getStatus());
    }
    @Test void savesPartialDraftsWithoutReplacingTheLastUsableStyle() {
        String id = UUID.randomUUID().toString();
        var partial = new ProductCaptureTemplateService.Variant(UUID.randomUUID().toString(), "", null, List.of(), "A");
        var draft = new ProductCaptureTemplateService.Save(0, "草稿", "", List.of(partial), true);
        var saved = service.save(id, draft, owner);
        assertTrue(saved.draft());
        assertNull(service.get(id, owner).variants().getFirst().image());
        assertEquals(id, service.settingsScope(id, owner, true));
        assertEquals(0, service.list(0, 50, true, owner).total());
        assertEquals(saved, service.save(id, draft, owner));

        var usable = service.save(id, value(saved.version(), "可用款式", upload()), owner);
        assertFalse(usable.draft());
        var edited = service.save(id, new ProductCaptureTemplateService.Save(
                usable.version(), "继续编辑", "", List.of(partial), true), owner);
        assertEquals("继续编辑", service.get(id, owner).name());
        assertTrue(edited.draft());
        assertEquals("可用款式", service.list(0, 50, true, owner).items().getFirst().name());
    }
    @Test void readsLegacyJsonWithoutCodesAndRequiresCodesOnNextSave() {
        String image=upload(),id=UUID.randomUUID().toString();var input=value(0,"legacy",image);service.save(id,input,owner);
        var legacy=new ArrayList<Map<String,Object>>();
        var v=new LinkedHashMap<String,Object>();v.put("id",input.variants().getFirst().id());v.put("name","正面");v.put("imageId",image);
        v.put("colors",List.of(Map.of("id",UUID.randomUUID().toString(),"name","白色","hex","#FFFFFF")));legacy.add(v);
        db.update("UPDATE product_capture_templates SET content_json=? WHERE id=?",json.writeValueAsString(legacy),id);
        var restored=service.get(id,owner).variants().getFirst();
        assertNull(restored.styleCode());assertNull(restored.colors().getFirst().colorCode());
        var incomplete=new ProductCaptureTemplateService.Variant(restored.id(),restored.name(),image,restored.colors(),restored.styleCode());
        assertThrows(BusinessException.class,()->service.save(id,new ProductCaptureTemplateService.Save(1,"legacy","",List.of(incomplete)),owner));
    }
    @Test void codeEditsRoundTripAndStaleCodeChangesConflict() {
        String image=upload(),id=UUID.randomUUID().toString();var created=value(0,"codes",image);service.save(id,created,owner);
        var v=created.variants().getFirst();var c=v.colors().getFirst();
        var update=new ProductCaptureTemplateService.Save(1,"codes","",List.of(new ProductCaptureTemplateService.Variant(v.id(),v.name(),image,List.of(new ProductCaptureTemplateService.Color(c.id(),c.name(),c.hex()," bk "))," b02 ")));
        service.save(id,update,owner);
        var restored=service.get(id,owner).variants().getFirst();assertEquals("B02",restored.styleCode());assertEquals("BK",restored.colors().getFirst().colorCode());
        var stale=new ProductCaptureTemplateService.Save(1,"codes","",created.variants());
        assertEquals(HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->service.save(id,stale,owner)).getStatus());
        assertEquals("B02",service.get(id,owner).variants().getFirst().styleCode());
    }
    @Test void duplicateUploadsAreStableAndStaleUpdatesCannotOverwriteNewerEdits(){
        String image=upload();assertEquals(image,upload());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM product_capture_template_images",Integer.class));
        String id=UUID.randomUUID().toString();var created=value(0,"original",image);service.save(id,created,owner);
        var update=value(1,"newer",image);assertEquals(2,service.save(id,update,owner).version());
        assertEquals(2,service.save(id,update,owner).version());
        assertEquals(HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->service.save(id,value(1,"stale",image),owner)).getStatus());
        assertEquals("newer",service.get(id,owner).name());
        service.save(UUID.randomUUID().toString(),value(0,"second",image),owner);
        assertEquals(2,service.list(0,1,owner).total());assertEquals(1,service.list(1,1,owner).items().size());
    }
    @Test void controllerRejectsAnonymousAndReadonlySavesAndServesAuthenticatedImage()throws Exception{
        String image=upload();var mvc=MockMvcBuilders.standaloneSetup(new ProductCaptureTemplateController(service)).setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/product-capture-templates")).andExpect(status().isUnauthorized());
        var readonly=ProductCaptureTemplateServiceTest.user("qa-org","product:view");
        try{
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(readonly,"",readonly.getAuthorities()));
            mvc.perform(put("/api/v1/product-capture-templates/"+UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(value(0,"x",image)))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/product-capture-templates/images/"+image)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(png));
        }finally{SecurityContextHolder.clearContext();}
    }
    @Test @EnabledIfEnvironmentVariable(named="CAPTURE_TEMPLATE_QA_REAL_OSS",matches="true")
    void realOssRoundTripWithDisposableOrganization()throws Exception{
        String org=UUID.randomUUID().toString();var user=ProductCaptureTemplateServiceTest.user(org,"product:edit");
        String bucket=System.getenv("CAPTURE_TEMPLATE_QA_OSS_BUCKET"),endpoint=System.getenv("CAPTURE_TEMPLATE_QA_OSS_ENDPOINT");
        var oss=new OssStorageService(bucket,"cn-chengdu",endpoint,endpoint,"",Duration.ofMinutes(5),false,null,System.getenv("CAPTURE_TEMPLATE_QA_OSS_CREDENTIALS"),"","");
        var realArchive=new CommerceImageArchive(Files.createTempDirectory("capture-template-qa-").toString(),oss);
        var real=new ProductCaptureTemplateService(db,realArchive,json);String image=null;
        try{
            var uploaded=real.upload(new MockMultipartFile("file","qa.png","image/png",png),user);image=uploaded.id();
            assertArrayEquals(png,Base64.getDecoder().decode(real.image(image,user).dataBase64()));
            var stored=real.save(UUID.randomUUID().toString(),value(0,"OSS QA",image),user);assertEquals(image,real.get(stored.id(),user).variants().getFirst().imageId());
            String key=db.queryForObject("SELECT storage_key FROM product_capture_template_images WHERE organization_id=? AND id=?",String.class,org,image);
            assertTrue(oss.exists(oss.commerceImageLocation(key)));assertFalse(stored.variants().getFirst().image().contains("oss://"));
        }finally{
            if(image!=null){String key=db.queryForObject("SELECT storage_key FROM product_capture_template_images WHERE organization_id=? AND id=?",String.class,org,image);oss.delete(oss.commerceImageLocation(key));}
            db.update("DELETE FROM product_capture_templates WHERE organization_id=?",org);db.update("DELETE FROM product_capture_template_images WHERE organization_id=?",org);oss.close();
        }
    }
}

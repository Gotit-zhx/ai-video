package com.yingdao.aicommerce.finance;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceAdHttpTest {
    private static final URI OFFICIAL=URI.create("https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/");
    @TempDir static Path temporary;
    static SSLContext validCertificate,wrongHostname;

    @BeforeAll static void certificates() throws Exception {
        validCertificate=certificate("business-api.tiktok.com");
        wrongHostname=certificate("unrelated.invalid");
    }

    @Test void unconfiguredAdvertisingReusesExistingDirectTransport(){
        var direct=mock(MarketplaceHttp.class);
        assertSame(direct,new FinanceAdHttp(direct,new ObjectMapper(),"",0).transport());
        assertSame(direct,new FinanceAdHttp(direct,new ObjectMapper(),"  ",0).transport());
        verifyNoInteractions(direct);
    }

    @Test void mountedSettingsCanSelectProxyWithoutChangingApplicationCredentials() throws Exception {
        var file=temporary.resolve("application-proxy.properties");
        Files.writeString(file,"app-id=12345678\napp-secret=unit-only-application-secret\n");
        var settings=new FinanceAdApplicationSettings("","",file.toString());var before=settings.current();
        Files.writeString(file,"app-id=12345678\napp-secret=unit-only-application-secret\nproxy-host=127.0.0.1\nproxy-port=3128\n");
        var direct=mock(MarketplaceHttp.class);
        var transport=new FinanceAdHttp(direct,new ObjectMapper(),"",0,file.toString());
        try{
            assertNotSame(direct,transport.transport());
            assertEquals(before,settings.current(),"Adding network settings must not change the grant revision or app secret");
        }finally{transport.stop();}
    }

    @Test void existingCredentialFileWithoutNetworkKeysKeepsDirectAccess() throws Exception {
        var file=temporary.resolve("application-direct.properties");Files.writeString(file,"app-id=12345678\napp-secret=unit-only-secret\n");
        var direct=mock(MarketplaceHttp.class);
        assertSame(direct,new FinanceAdHttp(direct,new ObjectMapper(),"",0,file.toString()).transport());
    }

    @Test void malformedPrivateFileNeverLeaksItsContents() throws Exception {
        var file=temporary.resolve("application-invalid.properties");Files.writeString(file,"proxy-port=unit-only-secret\n");
        var failure=assertThrows(IllegalArgumentException.class,()->new FinanceAdHttp(mock(MarketplaceHttp.class),new ObjectMapper(),"",0,file.toString()));
        assertFalse(failure.getMessage().contains("unit-only"));assertNull(failure.getCause());
    }

    @Test void explicitProxySettingsOverrideTheOptionalPrivateFile(){
        var direct=mock(MarketplaceHttp.class);
        var transport=new FinanceAdHttp(direct,new ObjectMapper(),"127.0.0.1",3128,temporary.resolve("missing-file").toString());
        try{assertNotSame(direct,transport.transport());}finally{transport.stop();}
    }

    @Test void springInjectsOneAdvertisingTransportIntoAuthorizationAndReportingWithoutChangingSharedClient() throws Exception {
        var direct=mock(MarketplaceHttp.class);
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("unit-only",Map.of(
                    "APP_TIKTOK_ADS_PROXY_HOST","127.0.0.1","APP_TIKTOK_ADS_PROXY_PORT","3128")));
            context.registerBean(MarketplaceHttp.class,()->direct);
            context.registerBean(ObjectMapper.class,()->new ObjectMapper());
            context.registerBean(FinanceRepository.class,()->mock(FinanceRepository.class));
            context.registerBean(FinanceService.class,()->mock(FinanceService.class));
            context.registerBean(com.yingdao.aicommerce.shop.application.ShopCredentialCipher.class,
                    ()->mock(com.yingdao.aicommerce.shop.application.ShopCredentialCipher.class));
            context.registerBean(com.yingdao.aicommerce.shop.application.ShopIntegrationSettings.class,
                    ()->mock(com.yingdao.aicommerce.shop.application.ShopIntegrationSettings.class));
            context.registerBean(FinanceShopAuthorization.class,()->mock(FinanceShopAuthorization.class));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                    ()->mock(org.springframework.transaction.PlatformTransactionManager.class));
            context.registerBean(FinanceAdApplicationSettings.class,()->mock(FinanceAdApplicationSettings.class));
            context.register(FinanceAdHttp.class,FinanceAds.class,FinanceAdAuthorization.class);context.refresh();
            var advertising=context.getBean(FinanceAdHttp.class).transport();
            assertNotSame(direct,advertising);assertSame(direct,context.getBean(MarketplaceHttp.class));
            assertSame(advertising,context.getBean(FinanceAds.class).http);
            var field=FinanceAdAuthorization.class.getDeclaredField("http");field.setAccessible(true);
            assertSame(advertising,field.get(context.getBean(FinanceAdAuthorization.class)));
            verifyNoInteractions(direct);
        }
    }

    @Test void partialConfigurationAndEmbeddedCredentialsAreRejectedWithoutEcho(){
        for(String host:List.of("","http://127.0.0.1","unit-user:unit-secret@relay.invalid","relay.invalid/path")){
            var failure=assertThrows(IllegalArgumentException.class,()->FinanceAdHttp.clientBuilder(host,3128));
            assertFalse(failure.getMessage().contains("unit-secret"));
        }
        for(int port:List.of(0,-1,65536))
            assertThrows(IllegalArgumentException.class,()->FinanceAdHttp.clientBuilder("127.0.0.1",port));
        assertThrows(IllegalArgumentException.class,()->new FinanceAdHttp(mock(MarketplaceHttp.class),new ObjectMapper(),"",3128));
    }

    @Test void configuredRouteAllowsOnlyOfficialHttpsAndNeverFallsBack() {
        try(var client=FinanceAdHttp.clientBuilder("127.0.0.1",3128).build()){
            var selector=client.proxy().orElseThrow();
            assertEquals(1,selector.select(OFFICIAL).size());
            assertEquals(new InetSocketAddress("127.0.0.1",3128),selector.select(OFFICIAL).getFirst().address());
            for(String value:List.of("http://business-api.tiktok.com/","https://example.invalid/",
                    "https://business-api.tiktok.com:444/","https://unit-user:unit-secret@business-api.tiktok.com/"))
                assertThrows(IllegalArgumentException.class,()->selector.select(URI.create(value)));
            selector.connectFailed(OFFICIAL,new InetSocketAddress("127.0.0.1",3128),new IOException("unit-only"));
            assertEquals(java.net.Proxy.Type.HTTP,selector.select(OFFICIAL).getFirst().type());
            assertEquals(java.net.http.HttpClient.Redirect.NEVER,client.followRedirects());
        }
    }

    @Test void httpsPostTraversesConnectWithoutResolvingOriginOrExposingPayloadToProxy() throws Exception {
        try(var origin=new Origin(validCertificate);var relay=new Relay(origin.port());
            var client=FinanceAdHttp.clientBuilder("127.0.0.1",relay.port()).sslContext(validCertificate).build()){
            var http=new MarketplaceHttp(new ObjectMapper(),client);
            var result=http.postJson(OFFICIAL,Map.of("Access-Token","unit-only-token"),"{\"auth_code\":\"unit-only-code\"}");
            assertEquals(0,((Number)result.get("code")).intValue());
            assertEquals(1,origin.requests.get(),"Authorization POST must not be replayed");
            assertEquals(1,relay.connects.size());
            assertTrue(relay.connects.getFirst().startsWith("CONNECT business-api.tiktok.com:443 HTTP/1.1\r\n"));
            assertFalse(relay.connects.getFirst().contains("unit-only"));
            assertEquals("{\"auth_code\":\"unit-only-code\"}",origin.body);
            assertEquals("unit-only-token",origin.token);
        }
    }

    @Test void tunnelRetainsDefaultCertificateTrustValidation() throws Exception {
        try(var origin=new Origin(validCertificate);var relay=new Relay(origin.port());
            var client=FinanceAdHttp.clientBuilder("127.0.0.1",relay.port()).build()){
            var failure=assertThrows(MarketplaceApiException.class,()->new MarketplaceHttp(new ObjectMapper(),client)
                    .postJson(OFFICIAL,Map.of(),"unit-only-code"));
            assertTrue(hasSslFailure(failure));
            assertEquals(0,origin.requests.get(),"Untrusted TLS peer must never receive an authorization code");
        }
    }

    @Test void trustedCertificateWithWrongHostnameIsStillRejected() throws Exception {
        try(var origin=new Origin(wrongHostname);var relay=new Relay(origin.port());
            var client=FinanceAdHttp.clientBuilder("127.0.0.1",relay.port()).sslContext(wrongHostname).build()){
            var failure=assertThrows(MarketplaceApiException.class,()->new MarketplaceHttp(new ObjectMapper(),client)
                    .postJson(OFFICIAL,Map.of(),"unit-only-code"));
            assertTrue(hasSslFailure(failure));
            assertEquals(0,origin.requests.get(),"Trusted CA must not bypass the official hostname check");
        }
    }

    static boolean hasSslFailure(Throwable error){
        for(int depth=0;error!=null&&depth<16;depth++,error=error.getCause())
            if(error instanceof javax.net.ssl.SSLException)return true;
        return false;
    }

    static SSLContext certificate(String host) throws Exception {
        var path=temporary.resolve(host+".p12");var password=UUID.randomUUID().toString();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","unit-only","-keyalg","RSA","-keysize","2048","-validity","1",
                "-dname","CN="+host,"-ext","SAN=dns:"+host,"-storetype","PKCS12","-keystore",path.toString(),
                "-storepass",password,"-keypass",password,"-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();fail("Temporary test certificate generation timed out");}
        assertEquals(0,process.exitValue());
        var store=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(path)){store.load(input,password.toCharArray());}
        var key=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());key.init(store,password.toCharArray());
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        var context=SSLContext.getInstance("TLS");context.init(key.getKeyManagers(),trust.getTrustManagers(),null);return context;
    }

    static final class Origin implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicInteger requests=new AtomicInteger();volatile String body,token;
        Origin(SSLContext context) throws IOException {
            server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));server.setExecutor(executor);
            server.createContext("/",exchange->{
                requests.incrementAndGet();body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                token=exchange.getRequestHeaders().getFirst("Access-Token");
                byte[] response="{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
            });server.start();
        }
        int port(){return server.getAddress().getPort();}
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }

    static final class Relay implements AutoCloseable {
        final ServerSocket listener=new ServerSocket();final ExecutorService executor=Executors.newCachedThreadPool();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final List<String> connects=new CopyOnWriteArrayList<>();
        Relay(int destinationPort) throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1",0));
            executor.submit(()->{while(!listener.isClosed())try{
                var socket=listener.accept();sockets.add(socket);executor.submit(()->forward(socket,destinationPort));
            }catch(IOException ignored){break;}});
        }
        int port(){return listener.getLocalPort();}
        void forward(Socket client,int destinationPort){
            try(client;var origin=new Socket()){
                client.setSoTimeout(5000);var bytes=new ByteArrayOutputStream();String header="";
                while(bytes.size()<4096&&!header.endsWith("\r\n\r\n")){
                    int next=client.getInputStream().read();if(next<0)return;bytes.write(next);header=bytes.toString(StandardCharsets.US_ASCII);
                }
                connects.add(header);if(!header.startsWith("CONNECT business-api.tiktok.com:443 HTTP/1.1\r\n"))return;
                origin.connect(new InetSocketAddress("127.0.0.1",destinationPort),1000);sockets.add(origin);
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                var response=executor.submit(()->{try{origin.getInputStream().transferTo(client.getOutputStream());}catch(IOException ignored){}});
                try{client.getInputStream().transferTo(origin.getOutputStream());}finally{response.cancel(true);sockets.remove(origin);}
            }catch(IOException ignored){}finally{sockets.remove(client);}
        }
        @Override public void close() throws IOException {
            listener.close();for(var socket:sockets)try{socket.close();}catch(IOException ignored){}executor.shutdownNow();
        }
    }
}

package com.yingdao.aicommerce.video.application.provider;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.ObjectMapper;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.MediaType;

class ProviderJsonSupportTest {
    @Test void retriesHandshakeFailureOnceThenKeepsSuccessfulTaskId() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.test/tasks")).andRespond(request->{throw new SSLHandshakeException("Remote host terminated handshake");});
        server.expect(requestTo("https://provider.test/tasks")).andRespond(withSuccess("{\"id\":\"accepted-once\"}",MediaType.APPLICATION_JSON));
        var provider=new ProviderJsonSupport(new ObjectMapper(),builder.build()){};
        assertEquals("accepted-once",provider.post("https://provider.test/tasks","test",Map.of()).get("id"));
        server.verify();
    }
    @Test void doesNotReplayUnknownTlsOrReadFailure() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.test/tasks")).andRespond(request->{throw new SSLException("SSL peer shut down incorrectly");});
        var provider=new ProviderJsonSupport(new ObjectMapper(),builder.build()){};
        var error=assertThrows(VideoProviderException.class,()->provider.post("https://provider.test/tasks","test",Map.of()));
        assertFalse(error.isSafeToFailover());
        server.verify();
        assertFalse(ProviderJsonSupport.failedBeforeRequest(new EOFException("truncated response")));
        assertFalse(ProviderJsonSupport.failedBeforeRequest(new java.net.SocketTimeoutException("read timeout")));
    }
    @Test void handshakeRetryIsBoundedAndReportsNotSent() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        for(int i=0;i<2;i++)server.expect(requestTo("https://provider.test/tasks")).andRespond(request->{throw new SSLHandshakeException("closed");});
        var provider=new ProviderJsonSupport(new ObjectMapper(),builder.build()){};
        var error=assertThrows(VideoProviderException.class,()->provider.post("https://provider.test/tasks","test",Map.of()));
        assertTrue(error.isSafeToFailover());
        assertTrue(error.getMessage().contains("尚未发送"));
        server.verify();
    }
}

package com.yingdao.aicommerce.shop.infrastructure;
import org.junit.jupiter.api.Test;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;
class MarketplaceImageDownloaderTest {
    @Test void rejectsNonPublicDestinations()throws Exception{
        for(String ip:new String[]{"127.0.0.1","10.1.2.3","100.64.0.1","169.254.169.254","192.168.0.1","198.18.0.1","192.0.2.1","203.0.113.1","::1","fc00::1","fe80::1","2001:db8::1"}){
            var address=InetAddress.getByName(ip);assertThrows(UnknownHostException.class,()->MarketplaceImageDownloader.publicAddresses(new InetAddress[]{address}),ip);
        }
    }
    @Test void mixedPublicAndPrivateAnswersRejected()throws Exception{var addresses=new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("10.0.0.1")};assertThrows(UnknownHostException.class,()->MarketplaceImageDownloader.publicAddresses(addresses));}
    @Test void resolvedPublicAddressIsReturnedForConnection()throws Exception{var addresses=new InetAddress[]{InetAddress.getByName("8.8.8.8")};assertSame(addresses,MarketplaceImageDownloader.publicAddresses(addresses));}
}

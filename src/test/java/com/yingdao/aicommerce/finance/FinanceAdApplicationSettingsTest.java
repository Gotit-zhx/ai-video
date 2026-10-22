package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdApplicationSettingsTest {
    @TempDir Path directory;

    @Test void absentSettingsDoNotPretendAuthorizationIsReady(){
        assertNull(new FinanceAdApplicationSettings("","","").current());
        assertThrows(BusinessException.class,()->new FinanceAdApplicationSettings("123456","","").current());
    }
    @Test void privateFileOverridesEnvironmentAndRotationInvalidatesOldLinks()throws Exception{
        Path file=directory.resolve("ads.properties");
        Files.writeString(file,"app-id=12345678\napp-secret=first-private-secret\n");
        var settings=new FinanceAdApplicationSettings("87654321","environment-secret",file.toString());
        var first=settings.current();
        assertEquals("12345678",first.appId());assertEquals("first-private-secret",first.secret());
        assertFalse(first.toString().contains(first.secret()));assertFalse(first.revision().contains(first.secret()));
        Files.writeString(file,"app-id=12345678\napp-secret=rotated-private-secret\n");
        assertNotEquals(first.revision(),settings.current().revision());
    }
    @Test void unreadableOrMalformedFileNeverFallsBackToAnotherApplication()throws Exception{
        Path file=directory.resolve("ads.properties");
        var settings=new FinanceAdApplicationSettings("87654321","environment-secret",file.toString());
        assertThrows(BusinessException.class,settings::current);
        Files.writeString(file,"app-id=12345678\napp-secret=short\n");
        assertThrows(BusinessException.class,settings::current);
        Files.writeString(file,"app-id=12345678\napp-secret=\\uZZZZ\n");
        var failure=assertThrows(BusinessException.class,settings::current);
        assertFalse(failure.getMessage().contains(file.toString()));
        Files.writeString(file,"");
        assertThrows(BusinessException.class,settings::current);
    }
}

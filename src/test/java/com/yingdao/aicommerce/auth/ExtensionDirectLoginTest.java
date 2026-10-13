package com.yingdao.aicommerce.auth;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ExtensionDirectLoginTest {
 @Test void badPasswordNeverIssuesExtensionCredential(){
  var service=mock(ExtensionAuthService.class);var manager=mock(AuthenticationManager.class);var controller=new ExtensionAuthController(service);
  ReflectionTestUtils.setField(controller,"authenticationManager",manager);ReflectionTestUtils.setField(controller,"defaultOrganizationCode","heyi");
  when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
  assertThrows(RuntimeException.class,()->controller.login(new ExtensionAuthController.DirectLogin("123","bad","device","extension","0.2.6")));
  verifyNoInteractions(service);
 }
 @Test void successfulPasswordLoginIssuesScopedDeviceTokenForAuthenticatedUser(){
  var service=mock(ExtensionAuthService.class);var manager=mock(AuthenticationManager.class);var controller=new ExtensionAuthController(service);
  ReflectionTestUtils.setField(controller,"authenticationManager",manager);ReflectionTestUtils.setField(controller,"defaultOrganizationCode","heyi");
  var user=new PlatformPrincipal("u","org","heyi","123","name","",true,List.of(),List.of("product:collect"));
  when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(user,null,List.of()));
  when(service.createPairingCode(user)).thenReturn(new ExtensionAuthService.PairingCodeResult("internal",Instant.now(),List.of("product:collect")));
  controller.login(new ExtensionAuthController.DirectLogin("123","password","device","extension","0.2.6"));
  verify(service).exchange(new ExtensionAuthService.ExchangeRequest("internal","device","extension","0.2.6"));
 }
}

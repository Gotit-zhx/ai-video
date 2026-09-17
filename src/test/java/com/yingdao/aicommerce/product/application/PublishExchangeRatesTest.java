package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.math.BigDecimal;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class PublishExchangeRatesTest {
 @Test void convertsEachSkuSeparatelyAndRoundsToTargetCurrency() {
  var request=Map.<String,Object>of("exchangeRateSnapshot",Map.of("rate","0.064953","targetCurrency","MYR"));
  assertEquals(new BigDecimal("10.07"),PublishExchangeRates.convert(new BigDecimal("155"),request));
  assertEquals(new BigDecimal("17.08"),PublishExchangeRates.convert(new BigDecimal("263"),request));
  assertEquals(new BigDecimal("2533"),PublishExchangeRates.convert(new BigDecimal("1.234"),Map.of("exchangeRateSnapshot",Map.of("rate","2052.35","targetCurrency","VND"))));
 }
 @Test void rejectsStaleMissingAndFailedRates() {
  var http=mock(MarketplaceHttp.class);var service=new PublishExchangeRates(http);
  for(var response:java.util.List.of(Map.<String,Object>of("result","error"),Map.<String,Object>of("result","success","base_code","PHP","rates",Map.of("MYR",0.06),"time_last_update_unix",1),Map.<String,Object>of("result","success","base_code","PHP","rates",Map.of(),"time_last_update_unix",Instant.now().getEpochSecond()))) {
   when(http.get(any(),any())).thenReturn(response);assertThrows(RuntimeException.class,()->service.snapshot("PHP","MY"));
  }
 }
 @Test void obtainsFreshQuoteAndSameCurrencyDoesNotCallNetwork() {
  var http=mock(MarketplaceHttp.class);var service=new PublishExchangeRates(http);
  assertEquals("1",service.snapshot("MYR","MY").get("rate"));verifyNoInteractions(http);
  when(http.get(any(),any())).thenReturn(Map.of("result","success","base_code","PHP","rates",Map.of("MYR",0.064953),"time_last_update_unix",Instant.now().getEpochSecond()));
  var quote=service.snapshot("PHP","MY");assertEquals("MYR",quote.get("targetCurrency"));assertEquals("0.064953",quote.get("rate"));
 }
}

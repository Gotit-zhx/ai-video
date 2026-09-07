package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class CommerceAdEvaluatorTest {
    final CommerceAdEvaluator evaluator=new CommerceAdEvaluator();
    CommerceAdEvaluator.Request request(int day,String spend,String gmv,long impressions,long orders,boolean settled){return new CommerceAdEvaluator.Request("PHP",day,7,impressions,orders,1000,5,new BigDecimal(spend),new BigDecimal(gmv),new BigDecimal("7000"),new BigDecimal("5"),new BigDecimal("6.5"),new BigDecimal("9"),settled);}
    @Test void qualifyingSampleBecomesOptimizationCandidate(){var result=evaluator.evaluate(request(7,"1000","5000",5000,10,true));assertEquals("OPTIMIZE_CANDIDATE",result.decision());assertEquals("PREVIEW_ONLY",result.mode());assertEquals(new BigDecimal("5.0000"),result.observedRoas());}
    @Test void budgetCapPrecedesStrongPerformance(){assertEquals("BUDGET_LIMIT_REVIEW",evaluator.evaluate(request(7,"7000","70000",5000,100,true)).decision());}
    @Test void sevenDaysAreRequired(){assertEquals("OBSERVE",evaluator.evaluate(request(6,"1000","6000",5000,10,true)).decision());}
    @Test void unsettledAttributionWaits(){assertEquals("WAIT_ATTRIBUTION",evaluator.evaluate(request(7,"1000","0",5000,0,false)).decision());}
    @Test void noSpendDoesNotDivideByZero(){var r=evaluator.evaluate(request(7,"0","0",0,0,true));assertNull(r.observedRoas());assertEquals("INSUFFICIENT_DELIVERY",r.decision());}
    @Test void insufficientOrdersNotWinner(){assertEquals("LOW_CONVERSION_REVIEW",evaluator.evaluate(request(7,"1","100",5000,1,true)).decision());}
    @Test void underperformerGetsSeparateRetestSuggestion(){var r=evaluator.evaluate(request(7,"1000","4000",5000,10,true));assertEquals("RETEST_REVIEW",r.decision());assertEquals(new BigDecimal("9"),r.proposedRetestTarget());}
    @Test void thresholdUsesUnroundedValues(){assertEquals("RETEST_REVIEW",evaluator.evaluate(request(7,"1000","4999.999",5000,10,true)).decision());}
    @Test void negativeSpendRejected(){assertThrows(RuntimeException.class,()->evaluator.evaluate(request(7,"-1","0",1,1,true)));}
}

package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution.Stage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommercePrintFlowOrderTest {
 @Test void printVariationsMustPassThroughMockupBeforeReviewAndPublish(){assertEquals(Stage.VARIATE,Stage.EXTRACT.next());assertEquals(Stage.MOCKUP,Stage.VARIATE.next());assertEquals(Stage.REVIEW,Stage.MOCKUP.next());assertEquals(Stage.PUBLISH,Stage.REVIEW.next());assertNull(Stage.PUBLISH.next());}
}

package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.mapper.ProductPublishWorkflowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Durably records the point after which a LIVE publication cannot be retried safely.
 *
 * <p>The marketplace provider invokes this in the outer publish transaction immediately before
 * its create-product request. REQUIRES_NEW is intentional: a process crash or rollback of the
 * audit transaction must not erase the duplicate-publication guard.</p>
 */
@Service
public class ProductPublishAttemptGuard {
    private final ProductPublishWorkflowMapper workflowMapper;

    public ProductPublishAttemptGuard(ProductPublishWorkflowMapper workflowMapper) {
        this.workflowMapper = workflowMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enterExternalCreateBoundary(String workflowId, String worker) {
        return workflowMapper.markPublicationAttempted(
                workflowId, worker, Instant.now()) == 1;
    }
}

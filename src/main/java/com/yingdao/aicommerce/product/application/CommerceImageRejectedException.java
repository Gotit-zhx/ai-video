package com.yingdao.aicommerce.product.application;

/** A confirmed runtime/provider rejection, with no unresolved network attempt. */
public final class CommerceImageRejectedException extends RuntimeException {
    private final String failureKind;
    private final Integer providerStatus;

    public CommerceImageRejectedException(String safeMessage,String failureKind,Integer providerStatus) {
        super(safeMessage);
        this.failureKind=failureKind;
        this.providerStatus=providerStatus;
    }

    public String failureKind(){return failureKind;}
    public Integer providerStatus(){return providerStatus;}
}

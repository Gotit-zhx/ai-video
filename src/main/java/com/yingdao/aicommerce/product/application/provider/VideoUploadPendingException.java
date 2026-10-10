package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** Resumable media staging only; must never authorize repeating ADD_ITEM. */
public class VideoUploadPendingException extends BusinessException {
    public VideoUploadPendingException(String message) { super(HttpStatus.SERVICE_UNAVAILABLE, message); }
    public VideoUploadPendingException(String message, Throwable cause) { this(message); initCause(cause); }
}

package com.yingdao.aicommerce.product.application;

/** No HTTP connection was leased, so this image attempt was never sent to the runtime. */
public final class ImageGenerationNotSentException extends RuntimeException {
    public ImageGenerationNotSentException(Throwable cause) {
        super("图片生成连接繁忙，请求尚未发送，已返回队列等待重试", cause);
    }
}

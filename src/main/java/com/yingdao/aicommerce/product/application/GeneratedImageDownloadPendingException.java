package com.yingdao.aicommerce.product.application;

/** A saved generation exists; transient failures keep downloading the same result. */
public class GeneratedImageDownloadPendingException extends RuntimeException {
    public GeneratedImageDownloadPendingException(String message,Throwable cause){super(message,cause);}
    public static int missingStatus(Throwable error) {
        for(int depth=0;error!=null&&depth<12;depth++,error=error.getCause()) {
            if(error instanceof com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException){
                if("读取商品图失败，HTTP 404".equals(error.getMessage()))return 404;
                if("读取商品图失败，HTTP 410".equals(error.getMessage()))return 410;
            }
        }
        return 0;
    }
    public static boolean retryable(Throwable error) {
        for(int depth=0;error!=null&&depth<12;depth++,error=error.getCause()) {
            if(error instanceof javax.net.ssl.SSLHandshakeException || error instanceof javax.net.ssl.SSLPeerUnverifiedException
                    || error instanceof InterruptedException)return false;
            if(error instanceof java.net.SocketException || error instanceof java.net.SocketTimeoutException
                    || error instanceof java.io.EOFException)return true;
            String message=error.getMessage();
            if(message!=null && message.matches("读取商品图失败，HTTP (408|425|429|500|502|503|504)"))return true;
        }
        return false;
    }
}

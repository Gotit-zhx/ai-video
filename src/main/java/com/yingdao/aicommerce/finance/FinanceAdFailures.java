package com.yingdao.aicommerce.finance;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;

/** Bounded, credential-free reasons suitable for the authorization receipt and UI. */
final class FinanceAdFailures {
    private FinanceAdFailures() {}
    enum Stage { CALLBACK, CONFIGURATION, TOKEN_EXCHANGE, ACCOUNT_DISCOVERY, ACCOUNT_INFO, ACCOUNT_SAVE }

    static final class ProviderFailure extends com.yingdao.aicommerce.common.exception.BusinessException {
        private final String code;
        ProviderFailure(Object code) {
            super(org.springframework.http.HttpStatus.BAD_REQUEST,"TikTok 广告接口拒绝请求（错误码 "+safeCode(code)+"），请核对授权权限。");
            this.code=safeCode(code);
        }
        private static String safeCode(Object code){String value=String.valueOf(code);return value.matches("-?\\d{1,10}")?value:"未知";}
    }

    static String message(Stage stage,RuntimeException failure) {
        boolean remote=stage==Stage.TOKEN_EXCHANGE||stage==Stage.ACCOUNT_DISCOVERY||stage==Stage.ACCOUNT_INFO;
        if(remote){
            boolean io=false;
            Throwable cause=failure;
            for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()){
                if(cause instanceof ProviderFailure provider)
                    return remote(stage,"PLATFORM","TikTok 广告平台拒绝请求（错误码 "+provider.code+"），请核对应用权限、授权账户及授权码后重新生成链接。");
                if(cause instanceof UnknownHostException)
                    return remote(stage,"DNS","服务器无法解析 TikTok 广告接口域名，请检查广告接口的 DNS 配置后重新授权。");
                if(cause instanceof SSLException)
                    return remote(stage,"TLS","服务器与 TikTok 广告接口的安全连接失败，请检查证书和网络配置，不能关闭证书校验。");
                if(cause instanceof HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)
                    return remote(stage,"TIMEOUT","服务器访问 TikTok 广告接口超时，请检查接口连通情况后重新授权。");
                if(cause instanceof ConnectException||cause instanceof NoRouteToHostException)
                    return remote(stage,"NETWORK","服务器无法连接 TikTok 广告接口，请检查接口连通情况后重新授权。");
                if(cause instanceof InterruptedException)
                    return remote(stage,"INTERRUPTED","服务器请求被中断，请重新生成授权链接。");
                if(cause instanceof IOException)io=true;
            }
            if(io)return remote(stage,"NETWORK","服务器读取 TikTok 广告接口失败，请检查网络后重新授权。");
        }
        return switch(stage){
            case CALLBACK -> "广告授权未完成 [CALLBACK]：平台取消了授权或未返回有效授权码，请重新生成链接并完成授权。";
            case CONFIGURATION -> "广告授权未完成 [CONFIGURATION]：广告应用配置不可用或已变更，请核对服务端广告配置后重新生成链接。";
            case TOKEN_EXCHANGE -> "广告授权未完成 [TOKEN_EXCHANGE]：平台未返回有效授权信息，请核对应用权限后重新生成链接。";
            case ACCOUNT_DISCOVERY -> "广告授权未完成 [ACCOUNT_DISCOVERY]：未取得符合本次要求的广告账户，请核对所选账户及其授权权限。";
            case ACCOUNT_INFO -> "广告授权未完成 [ACCOUNT_INFO]：未能验证全部授权账户的信息，尚未保存账户，请核对账户访问权限。";
            case ACCOUNT_SAVE -> "广告授权未完成 [ACCOUNT_SAVE]：账户验证后保存失败，未保存部分账户，请管理员检查账户归属及数据库状态。";
        };
    }
    private static String remote(Stage stage,String reason,String message){
        return "广告授权未完成 ["+stage+"]["+reason+"]："+message;
    }
}

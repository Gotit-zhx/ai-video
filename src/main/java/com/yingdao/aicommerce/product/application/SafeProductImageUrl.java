package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

@Component
public class SafeProductImageUrl {
    public String validate(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片地址不能为空且不能超过 4096 个字符");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片地址格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getPort() > 0 && uri.getPort() != 443) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片必须使用标准 HTTPS 地址");
        }
        String host;
        try {
            host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片域名不正确");
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.equals("0.0.0.0") || host.equals("::1") || host.startsWith("127.")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片地址不能指向本机或内网");
        }
        if (isIpLiteral(host)) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片地址不能指向本机或内网");
                }
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片 IP 地址不正确");
            }
        }
        try {
            return new URI("https", null, host, -1,
                    uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath(),
                    uri.getRawQuery(), null).toASCIIString();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品图片地址格式不正确");
        }
    }

    private boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }
}

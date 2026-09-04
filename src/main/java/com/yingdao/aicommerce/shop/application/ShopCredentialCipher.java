package com.yingdao.aicommerce.shop.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ShopCredentialCipher {
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public ShopCredentialCipher(@Value("${app.shop-auth.credential-key}") String credentialKey) {
        if (credentialKey == null || credentialKey.length() < 32) {
            throw new IllegalStateException("APP_SHOP_CREDENTIAL_KEY must contain at least 32 characters");
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(credentialKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize shop credential cipher", exception);
        }
    }

    public String encrypt(String plaintext, String organizationId, String shopId) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(organizationId, shopId));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array();
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt shop credential", exception);
        }
    }

    public String decrypt(String ciphertext, String organizationId, String shopId) {
        if (ciphertext == null) return null;
        if (!ciphertext.startsWith("v1:")) {
            throw new IllegalStateException("Unsupported shop credential format");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(3));
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(organizationId, shopId));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to decrypt shop credential", exception);
        }
    }

    private byte[] aad(String organizationId, String shopId) {
        return (organizationId + ":" + shopId).getBytes(StandardCharsets.UTF_8);
    }
}

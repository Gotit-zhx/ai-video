package com.yingdao.aicommerce.saas.application;

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
public class ModelSecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public ModelSecretCipher(
            @Value("${app.model-secret-key}") String modelSecretKey
    ) {
        if (modelSecretKey == null || modelSecretKey.length() < 24) {
            throw new IllegalStateException(
                    "APP_MODEL_SECRET_KEY must contain at least 24 characters"
            );
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(modelSecretKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize model secret cipher", exception);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt model API key", exception);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(3));
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to decrypt model API key", exception);
        }
    }
}

/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexify.server.apppassword;

import nu.fgv.spexify.server.config.SpexifyProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encrypts app passwords with AES-256-GCM, and makes passwords, keys and hashes.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class PasswordCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String PASSWORD_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String KEY_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public PasswordCrypto(final SpexifyProperties properties) {
        final String encoded = properties.crypto() == null ? null : properties.crypto().appPasswordEncryptionKey();

        if (encoded == null || encoded.isBlank()) {
            // Never fall back to a known key: app passwords encrypted with it could be read by anyone
            throw new IllegalStateException("API_APP_PASSWORD_ENCRYPTION_KEY is not set");
        }

        final byte[] bytes = Base64.getDecoder().decode(encoded.strip());

        this.key = new SecretKeySpec(sha256(bytes), "AES");
    }

    public static String sha256Hex(final String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String md5Hex(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(final String a, final String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(final byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] encrypt(final String plain) {
        try {
            final byte[] iv = new byte[IV_LENGTH];

            random.nextBytes(iv);

            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            final byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            return ByteBuffer.allocate(IV_LENGTH + encrypted.length).put(iv).put(encrypted).array();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    public String decrypt(final byte[] ciphertext) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_LENGTH));
            return new String(cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt", e);
        }
    }

    /**
     * A new app password: 24 characters without look-alikes, in groups of four for typing on a phone.
     */
    public String newPassword() {
        final StringBuilder password = new StringBuilder();

        for (int i = 0; i < 24; i++) {
            if (i > 0 && i % 4 == 0) {
                password.append('-');
            }
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    public String newApiKey() {
        final StringBuilder apiKey = new StringBuilder("spx_");

        for (int i = 0; i < 40; i++) {
            apiKey.append(KEY_ALPHABET.charAt(random.nextInt(KEY_ALPHABET.length())));
        }
        return apiKey.toString();
    }
}

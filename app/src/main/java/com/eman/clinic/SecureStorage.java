package com.eman.clinic;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Android Keystore-backed value store with transparent plaintext migration. */
final class SecureStorage {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "clinic_secure_storage_v1";
    private static final String PREFIX = "enc-v1:";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences preferences;

    SecureStorage(Context context, String preferenceName) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
    }

    synchronized String getString(String key, String fallback) {
        Object raw = preferences.getAll().get(key);
        if (raw == null) return fallback;
        String stored = String.valueOf(raw);
        if (!stored.startsWith(PREFIX)) {
            putString(key, stored);
            return stored;
        }
        try {
            byte[] packed = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (packed.length <= 12) throw new IllegalStateException("Invalid encrypted value");
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Secure value cannot be decrypted", e);
        }
    }

    synchronized void putString(String key, String value) {
        if (value == null) {
            preferences.edit().remove(key).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            preferences.edit().putString(key, PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Secure value cannot be stored", e);
        }
    }

    synchronized void clear() {
        preferences.edit().clear().apply();
    }

    synchronized boolean getBoolean(String key, boolean fallback) {
        String value = getString(key, fallback ? "true" : "false");
        return "true".equalsIgnoreCase(value);
    }

    synchronized void putBoolean(String key, boolean value) {
        putString(key, value ? "true" : "false");
    }

    synchronized long getLong(String key, long fallback) {
        try { return Long.parseLong(getString(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    synchronized void putLong(String key, long value) {
        putString(key, String.valueOf(value));
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}

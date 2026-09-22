package com.eman.clinic;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password encryption for portable Clinic backup files. */
public final class BackupCrypto {
    private static final String MAGIC = "CLINIC-BACKUP-1";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;

    private BackupCrypto() {}

    public static String encrypt(byte[] plaintext, char[] password) throws GeneralSecurityException {
        validatePassword(password);
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);

        String kdf = preferredKdf();
        SecretKeySpec key = derive(password, salt, ITERATIONS, kdf);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad(kdf, ITERATIONS));
        byte[] ciphertext = cipher.doFinal(plaintext == null ? new byte[0] : plaintext);

        return MAGIC + "\n" + kdf + "\n" + ITERATIONS + "\n" +
                hex(salt) + "\n" + hex(iv) + "\n" + hex(ciphertext);
    }

    public static byte[] decrypt(String envelope, char[] password) throws GeneralSecurityException {
        validatePassword(password);
        if (envelope == null) throw new GeneralSecurityException("invalid_backup");
        String[] parts = envelope.trim().split("\\n", 6);
        if (parts.length != 6 || !MAGIC.equals(parts[0])) throw new GeneralSecurityException("invalid_backup");

        String kdf = parts[1];
        int iterations;
        try { iterations = Integer.parseInt(parts[2]); }
        catch (Exception e) { throw new GeneralSecurityException("invalid_backup"); }
        if (iterations < 100_000 || iterations > 1_000_000) throw new GeneralSecurityException("invalid_backup");

        byte[] salt = unhex(parts[3]);
        byte[] iv = unhex(parts[4]);
        byte[] ciphertext = unhex(parts[5]);
        if (salt.length < 16 || iv.length != IV_BYTES || ciphertext.length < 16)
            throw new GeneralSecurityException("invalid_backup");

        SecretKeySpec key = derive(password, salt, iterations, kdf);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad(kdf, iterations));
        try {
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new GeneralSecurityException("wrong_password_or_corrupt_backup");
        }
    }

    private static String preferredKdf() throws GeneralSecurityException {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return "PBKDF2WithHmacSHA256";
        } catch (GeneralSecurityException ignored) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return "PBKDF2WithHmacSHA1";
        }
    }

    private static SecretKeySpec derive(char[] password, byte[] salt, int iterations, String kdf)
            throws GeneralSecurityException {
        if (!("PBKDF2WithHmacSHA256".equals(kdf) || "PBKDF2WithHmacSHA1".equals(kdf)))
            throw new GeneralSecurityException("unsupported_backup_kdf");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] raw = SecretKeyFactory.getInstance(kdf).generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] aad(String kdf, int iterations) {
        return (MAGIC + "|" + kdf + "|" + iterations).getBytes(StandardCharsets.UTF_8);
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 8)
            throw new IllegalArgumentException("backup_password_too_short");
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] h = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = h[v >>> 4];
            out[i * 2 + 1] = h[v & 0x0f];
        }
        return new String(out);
    }

    private static byte[] unhex(String value) throws GeneralSecurityException {
        if (value == null || (value.length() & 1) != 0) throw new GeneralSecurityException("invalid_backup");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new GeneralSecurityException("invalid_backup");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
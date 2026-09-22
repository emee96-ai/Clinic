package com.eman.clinic;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupCryptoTest {
    @Test public void encryptedBackupRoundTrips() throws Exception {
        byte[] source = "patient=أحمد|visit=42|paid=10000".getBytes(StandardCharsets.UTF_8);
        char[] password = "Strong-Backup-2026".toCharArray();
        String encrypted = BackupCrypto.encrypt(source, password);

        assertTrue(encrypted.startsWith("CLINIC-BACKUP-1\n"));
        assertNotEquals(new String(source, StandardCharsets.UTF_8), encrypted);
        assertArrayEquals(source, BackupCrypto.decrypt(encrypted, password));
    }

    @Test public void wrongPasswordIsRejected() throws Exception {
        String encrypted = BackupCrypto.encrypt("secret".getBytes(StandardCharsets.UTF_8), "correct-password".toCharArray());
        try {
            BackupCrypto.decrypt(encrypted, "wrong-password".toCharArray());
            fail("wrong password must fail");
        } catch (GeneralSecurityException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("wrong_password_or_corrupt_backup"));
        }
    }

    @Test public void tamperedBackupIsRejected() throws Exception {
        char[] password = "tamper-check-123".toCharArray();
        String encrypted = BackupCrypto.encrypt("important".getBytes(StandardCharsets.UTF_8), password);
        char last = encrypted.charAt(encrypted.length() - 1);
        String tampered = encrypted.substring(0, encrypted.length() - 1) + (last == '0' ? '1' : '0');
        try {
            BackupCrypto.decrypt(tampered, password);
            fail("tampered backup must fail");
        } catch (GeneralSecurityException expected) {
            // AES-GCM authentication must reject modified ciphertext.
        }
    }

    @Test public void shortPasswordIsRejected() throws Exception {
        try {
            BackupCrypto.encrypt(new byte[]{1,2,3}, "short".toCharArray());
            fail("short password must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("backup_password_too_short"));
        }
    }
}
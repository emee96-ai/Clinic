package com.eman.clinic;

import android.content.Context;
import android.util.Base64;

import java.security.SecureRandom;

/** Generates one random SQLCipher passphrase per clinic and protects it with Android Keystore. */
final class DatabaseKeyManager {
    private static final String PREF = "clinic_database_keys";
    private static final int KEY_BYTES = 32;

    private DatabaseKeyManager() {}

    static byte[] getOrCreate(Context context, String scopeId) {
        SecureStorage storage = new SecureStorage(context, PREF);
        String keyName = ClinicDatabaseScope.keyName(scopeId);
        String encoded = storage.getString(keyName, "");
        if (!encoded.isEmpty()) {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
            if (decoded.length != KEY_BYTES) throw new IllegalStateException("Invalid clinic database key");
            return decoded;
        }
        byte[] generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        storage.putString(keyName, Base64.encodeToString(generated, Base64.NO_WRAP));
        return generated;
    }
}

package com.eman.clinic;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted peer-to-peer sync for phones connected to the same Wi-Fi / hotspot.
 * Internet is not required. The existing sync_dirty outbox remains the source of
 * truth, so any device that later gets internet can still upload changes to Supabase.
 * LAN payloads are filtered by the signed-in clinic member permissions.
 */
public final class LocalSyncManager {
    private static final String PREF = "clinic_lan_sync";
    private static final int UDP_PORT = 39431;
    private static final int TCP_PORT = 39432;
    private static final String MAGIC = "clinic-lan-v1";
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2);
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, Long> LAST_CONNECT = new ConcurrentHashMap<>();
    private static Context app;

    private LocalSyncManager() {}

    public static void start(Context context) {
        app = context.getApplicationContext();
        if (!STARTED.compareAndSet(false, true)) return;
        IO.execute(LocalSyncManager::udpServerLoop);
        IO.execute(LocalSyncManager::tcpServerLoop);
        TIMER.scheduleWithFixedDelay(LocalSyncManager::discoverSafe, 3, 8, TimeUnit.SECONDS);
    }

    public static void kick(Context context) {
        app = context.getApplicationContext();
        IO.execute(LocalSyncManager::discoverSafe);
    }

    public static String pairKey(Context context) {
        return prefs(context).getString("pair_key", "");
    }

    public static void savePairKey(Context context, String key) {
        String normalized = key == null ? "" : key.trim().replace(" ", "").toUpperCase(Locale.US);
        prefs(context).edit().putString("pair_key", normalized).remove("last_error").apply();
        kick(context);
    }

    public static String generatePairKey() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING).toUpperCase(Locale.US);
    }

    public static String status(Context context) {
        SharedPreferences p = prefs(context);
        String key = p.getString("pair_key", "");
        if (key.length() < 16) return "المزامنة المحلية غير مربوطة بعد";
        String error = p.getString("last_error", "");
        String last = p.getString("last_sync_at", "");
        String peer = p.getString("last_peer", "");
        if (!last.isEmpty()) return "آخر مزامنة محلية: " + last + (peer.isEmpty() ? "" : " • " + peer);
        if (!error.isEmpty()) return "جاهزة للربط • " + error;
        return "جاهزة • وصّلي الجهازين بنفس Wi‑Fi أو Hotspot";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean configured() {
        Context c = app;
        if (c == null) return false;
        AuthStore auth = new AuthStore(c);
        return !auth.clinicId().isEmpty() && pairKey(c).length() >= 16;
    }

    private static void discoverSafe() {
        try {
            if (!configured()) return;
            Context c = app;
            AuthStore auth = new AuthStore(c);
            SyncStore store = new SyncStore(c);
            JSONObject msg = signedMessage("discover", auth.clinicId(), store.deviceId());
            byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), UDP_PORT);
            socket.send(packet);
            socket.close();
        } catch (Exception e) {
            noteError("تعذر البحث عن جهاز قريب");
        }
    }

    private static void udpServerLoop() {
        while (true) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(UDP_PORT));
                byte[] buffer = new byte[4096];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    if (!configured()) continue;
                    String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    JSONObject msg = new JSONObject(raw);
                    if (!MAGIC.equals(msg.optString("magic"))) continue;
                    String type = msg.optString("type", "");
                    if (!verifySigned(msg)) continue;
                    String remoteDevice = msg.optString("device_id", "");
                    String localDevice = new SyncStore(app).deviceId();
                    if (remoteDevice.isEmpty() || remoteDevice.equals(localDevice)) continue;
                    if ("discover".equals(type)) {
                        AuthStore auth = new AuthStore(app);
                        JSONObject offer = signedMessage("offer", auth.clinicId(), localDevice);
                        byte[] out = offer.toString().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(out, out.length, packet.getAddress(), UDP_PORT));
                    } else if ("offer".equals(type)) {
                        connectToPeer(packet.getAddress(), remoteDevice);
                    }
                }
            } catch (Exception ignored) {
                sleep(1500);
            } finally {
                if (socket != null) socket.close();
            }
        }
    }

    private static void tcpServerLoop() {
        while (true) {
            ServerSocket server = null;
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(TCP_PORT));
                while (true) {
                    Socket socket = server.accept();
                    IO.execute(() -> handleIncoming(socket));
                }
            } catch (Exception ignored) {
                sleep(1500);
            } finally {
                try { if (server != null) server.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void connectToPeer(InetAddress address, String remoteDevice) {
        String key = address.getHostAddress() + ":" + remoteDevice;
        long now = System.currentTimeMillis();
        Long previous = LAST_CONNECT.put(key, now);
        if (previous != null && now - previous < 5000) return;
        IO.execute(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, TCP_PORT), 1800);
                socket.setSoTimeout(6000);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                AuthStore auth = new AuthStore(app);
                SyncStore store = new SyncStore(app);
                writeLine(out, signedMessage("hello", auth.clinicId(), store.deviceId()).toString());
                JSONObject ack = new JSONObject(readLine(in));
                if (!"hello_ack".equals(ack.optString("type")) || !verifySigned(ack)) return;
                writeLine(out, encrypt(buildBatch().toString()).toString());
                JSONObject peerBatch = decrypt(new JSONObject(readLine(in)));
                applyBatch(peerBatch);
                noteSuccess(remoteDevice);
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        });
    }

    private static void handleIncoming(Socket socket) {
        try {
            socket.setSoTimeout(6000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            JSONObject hello = new JSONObject(readLine(in));
            if (!"hello".equals(hello.optString("type")) || !verifySigned(hello)) return;
            String remoteDevice = hello.optString("device_id", "");
            SyncStore store = new SyncStore(app);
            AuthStore auth = new AuthStore(app);
            writeLine(out, signedMessage("hello_ack", auth.clinicId(), store.deviceId()).toString());
            JSONObject incoming = decrypt(new JSONObject(readLine(in)));
            applyBatch(incoming);
            writeLine(out, encrypt(buildBatch().toString()).toString());
            noteSuccess(remoteDevice);
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static JSONObject buildBatch() throws Exception {
        Context c = app;
        SyncStore store = new SyncStore(c);
        AuthStore auth = new AuthStore(c);
        String localDevice = store.deviceId();
        JSONArray items = new JSONArray();
        Set<String> included = new HashSet<>();
        List<SyncStore.SyncItem> pending = store.pending(300);
        for (SyncStore.SyncItem item : pending) {
            if (!canSend(auth, item.entityType)) continue;
            JSONObject payload = new JSONObject(item.payload);
            sanitizeOutgoing(auth, item.entityType, payload);
            if ("visit".equals(item.entityType)) {
                String patientKey = payload.optString("patient_sync_key", "");
                if (!patientKey.isEmpty() && auth.can("view_patients")) {
                    addPatientDependency(items, included, patientKey, localDevice);
                }
            }
            JSONObject e = envelope(item.entityType, item.syncKey, item.changedAt,
                    originFor(item.entityType, item.syncKey, item.changedAt, localDevice), payload, false);
            items.put(e);
            included.add(item.entityType + ":" + item.syncKey);
        }
        JSONObject batch = new JSONObject();
        batch.put("mode", "batch");
        batch.put("clinic_id", auth.clinicId());
        batch.put("sender_device", localDevice);
        batch.put("items", items);
        return batch;
    }

    private static void sanitizeOutgoing(AuthStore auth, String type, JSONObject payload) {
        if (!"visit".equals(type)) return;
        if (!auth.can("edit_clinical")) stripClinical(payload);
        if (!auth.can("manage_queue")) {
            payload.remove("status");
            payload.remove("started_at");
            payload.remove("completed_at");
        }
        if (!(auth.can("register_visits") || auth.can("record_payments") || auth.can("view_finance"))) {
            payload.remove("fee");
            payload.remove("paid_amount");
        }
    }

    private static void addPatientDependency(JSONArray items, Set<String> included, String syncKey, String localDevice) {
        String composite = "patient:" + syncKey;
        if (included.contains(composite)) return;
        ClinicDb helper = new ClinicDb(app);
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT p.card_no,p.full_name,p.phone,p.gender,p.created_at FROM patients p " +
                        "JOIN sync_entity_keys k ON k.entity_type='patient' AND k.local_id=p.id WHERE k.sync_key=? LIMIT 1",
                new String[]{syncKey});
        try {
            if (!c.moveToFirst()) return;
            JSONObject p = new JSONObject();
            p.put("sync_key", syncKey);
            p.put("card_no", c.getInt(0));
            p.put("full_name", c.getString(1));
            p.put("phone", c.getString(2));
            p.put("gender", c.getString(3));
            p.put("created_at", c.getString(4));
            items.put(envelope("patient", syncKey, "", localDevice, p, true));
            included.add(composite);
        } catch (Exception ignored) {
        } finally { c.close(); }
    }

    private static JSONObject envelope(String type, String syncKey, String changedAt, String origin,
                                       JSONObject payload, boolean dependencyOnly) throws Exception {
        JSONObject e = new JSONObject();
        e.put("entity_type", type);
        e.put("sync_key", syncKey);
        e.put("changed_at", changedAt == null ? "" : changedAt);
        e.put("origin_device", origin == null ? "" : origin);
        e.put("dependency_only", dependencyOnly);
        e.put("payload", payload);
        return e;
    }

    private static void applyBatch(JSONObject batch) {
        try {
            AuthStore auth = new AuthStore(app);
            if (!"batch".equals(batch.optString("mode"))) return;
            if (!auth.clinicId().equals(batch.optString("clinic_id"))) return;
            JSONArray items = batch.optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) applyEnvelope(items.getJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    private static void applyEnvelope(JSONObject e) {
        String type = e.optString("entity_type", "");
        String syncKey = e.optString("sync_key", "");
        String incomingChanged = e.optString("changed_at", "");
        String incomingOrigin = e.optString("origin_device", "");
        boolean dependencyOnly = e.optBoolean("dependency_only", false);
        JSONObject payload = e.optJSONObject("payload");
        if (type.isEmpty() || syncKey.isEmpty() || payload == null) return;

        AuthStore auth = new AuthStore(app);
        if (!canReceive(auth, type)) return;

        SyncStore store = new SyncStore(app);
        Long localId = store.localIdForKey(type, syncKey);
        if (dependencyOnly && localId != null) return;

        if (!dependencyOnly && localId != null && store.isDirty(type, localId)) {
            String localChanged = dirtyChangedAt(type, localId);
            String localOrigin = originFor(type, syncKey, localChanged, store.deviceId());
            if (compareVersion(incomingChanged, incomingOrigin, localChanged, localOrigin) <= 0) return;
            new ClinicDb(app).getWritableDatabase().delete("sync_dirty", "entity_type=? AND local_id=?",
                    new String[]{type, String.valueOf(localId)});
        }

        JSONObject row = cloneJson(payload);
        try {
            row.put("sync_key", syncKey);
            if ("visit".equals(type)) {
                if (!auth.can("view_clinical")) stripClinical(row);
                applyLanVisit(store, row, syncKey);
            } else if ("patient".equals(type)) {
                if (hasCardCollision(syncKey, row.optInt("card_no", -1))) {
                    noteError("يوجد تعارض في رقم كرت " + row.optInt("card_no", 0));
                    return;
                }
                store.applyRemotePatient(row);
            } else if ("payment".equals(type)) {
                JSONObject visit = new JSONObject();
                visit.put("sync_key", row.optString("visit_sync_key", ""));
                row.put("visit", visit);
                store.applyRemotePayment(row);
            } else if ("day_closure".equals(type)) {
                store.applyRemoteDayClosure(row);
            } else return;
        } catch (Exception ignored) { return; }

        Long appliedId = store.localIdForKey(type, syncKey);
        if (appliedId == null || dependencyOnly) return;
        if (canSend(auth, type)) markDirty(type, appliedId, incomingChanged);
        store.putMeta(versionMeta(type, syncKey), incomingChanged);
        store.putMeta(originMeta(type, syncKey), incomingOrigin);
    }

    private static void applyLanVisit(SyncStore store, JSONObject row, String syncKey) {
        String patientKey = row.optString("patient_sync_key", "");
        Long patientLocalId = store.localIdForKey("patient", patientKey);
        if (patientLocalId == null) return;
        Long localId = store.localIdForKey("visit", syncKey);

        ContentValues v = new ContentValues();
        v.put("patient_id", patientLocalId);
        putStringIfPresent(v, row, "visit_type");
        putStringIfPresent(v, row, "status");
        putIntIfPresent(v, row, "fee");
        putIntIfPresent(v, row, "paid_amount");
        putStringIfPresent(v, row, "complaint");
        putStringIfPresent(v, row, "exam");
        putStringIfPresent(v, row, "diagnosis");
        putStringIfPresent(v, row, "labs");
        putStringIfPresent(v, row, "treatment");
        putStringIfPresent(v, row, "followup");
        putStringIfPresent(v, row, "created_at");
        putNullableStringIfPresent(v, row, "started_at");
        putNullableStringIfPresent(v, row, "completed_at");

        if (localId == null) {
            if (!row.has("visit_type") || !row.has("status") || !row.has("created_at")) return;
        }

        SQLiteDatabase db = new ClinicDb(app).getWritableDatabase();
        store.putMeta("suppress_tracking", "1");
        try {
            long id;
            if (localId == null) id = db.insert("visits", null, v);
            else {
                db.update("visits", v, "id=?", new String[]{String.valueOf(localId)});
                id = localId;
            }
            if (id > 0) store.bindRemoteKey("visit", id, syncKey);
        } finally {
            store.putMeta("suppress_tracking", "0");
        }
    }

    private static void putStringIfPresent(ContentValues v, JSONObject row, String key) {
        if (row.has(key) && !row.isNull(key)) v.put(key, row.optString(key, ""));
    }

    private static void putNullableStringIfPresent(ContentValues v, JSONObject row, String key) {
        if (!row.has(key)) return;
        if (row.isNull(key) || row.optString(key, "").isEmpty()) v.putNull(key);
        else v.put(key, row.optString(key, ""));
    }

    private static void putIntIfPresent(ContentValues v, JSONObject row, String key) {
        if (row.has(key) && !row.isNull(key)) v.put(key, row.optInt(key, 0));
    }

    private static void stripClinical(JSONObject payload) {
        payload.remove("complaint");
        payload.remove("exam");
        payload.remove("diagnosis");
        payload.remove("labs");
        payload.remove("treatment");
        payload.remove("followup");
    }

    private static boolean canSend(AuthStore auth, String type) {
        if ("patient".equals(type)) return auth.can("edit_patients");
        if ("visit".equals(type)) return auth.can("register_visits") || auth.can("manage_queue") || auth.can("edit_clinical");
        if ("payment".equals(type)) return auth.can("record_payments");
        if ("day_closure".equals(type)) return auth.can("close_day");
        return false;
    }

    private static boolean canReceive(AuthStore auth, String type) {
        if ("patient".equals(type)) return auth.can("view_patients");
        if ("visit".equals(type)) return auth.can("manage_queue") || auth.can("view_clinical");
        if ("payment".equals(type)) return auth.can("view_finance") || auth.can("record_payments");
        if ("day_closure".equals(type)) return auth.can("view_finance") || auth.can("close_day");
        return false;
    }

    private static boolean hasCardCollision(String syncKey, int cardNo) {
        if (cardNo < 0) return false;
        ClinicDb helper = new ClinicDb(app);
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT k.sync_key FROM patients p LEFT JOIN sync_entity_keys k ON k.entity_type='patient' AND k.local_id=p.id " +
                        "WHERE p.card_no=? LIMIT 1", new String[]{String.valueOf(cardNo)});
        boolean collision = c.moveToFirst() && c.getString(0) != null && !syncKey.equals(c.getString(0));
        c.close();
        return collision;
    }

    private static String dirtyChangedAt(String type, long localId) {
        ClinicDb helper = new ClinicDb(app);
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT changed_at FROM sync_dirty WHERE entity_type=? AND local_id=? LIMIT 1",
                new String[]{type, String.valueOf(localId)});
        String value = c.moveToFirst() ? c.getString(0) : "";
        c.close();
        return value == null ? "" : value;
    }

    private static void markDirty(String type, long localId, String changedAt) {
        if (changedAt == null || changedAt.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("entity_type", type);
        v.put("local_id", localId);
        v.put("changed_at", changedAt);
        new ClinicDb(app).getWritableDatabase().insertWithOnConflict(
                "sync_dirty", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String originFor(String type, String syncKey, String changedAt, String fallbackDevice) {
        SyncStore store = new SyncStore(app);
        String savedVersion = store.meta(versionMeta(type, syncKey));
        String savedOrigin = store.meta(originMeta(type, syncKey));
        if (changedAt != null && changedAt.equals(savedVersion) && !savedOrigin.isEmpty()) return savedOrigin;
        store.putMeta(versionMeta(type, syncKey), changedAt == null ? "" : changedAt);
        store.putMeta(originMeta(type, syncKey), fallbackDevice);
        return fallbackDevice;
    }

    private static String versionMeta(String type, String syncKey) { return "lan_v_" + type + "_" + syncKey; }
    private static String originMeta(String type, String syncKey) { return "lan_o_" + type + "_" + syncKey; }

    private static int compareVersion(String aTime, String aOrigin, String bTime, String bOrigin) {
        String a = aTime == null ? "" : aTime;
        String b = bTime == null ? "" : bTime;
        int t = a.compareTo(b);
        if (t != 0) return t;
        return (aOrigin == null ? "" : aOrigin).compareTo(bOrigin == null ? "" : bOrigin);
    }

    private static JSONObject signedMessage(String type, String clinicId, String deviceId) throws Exception {
        JSONObject o = new JSONObject();
        String nonce = randomNonce();
        o.put("magic", MAGIC);
        o.put("type", type);
        o.put("clinic_id", clinicId);
        o.put("device_id", deviceId);
        o.put("nonce", nonce);
        o.put("proof", hmac(type + "|" + clinicId + "|" + deviceId + "|" + nonce));
        return o;
    }

    private static boolean verifySigned(JSONObject o) {
        try {
            AuthStore auth = new AuthStore(app);
            if (!MAGIC.equals(o.optString("magic"))) return false;
            if (!auth.clinicId().equals(o.optString("clinic_id"))) return false;
            String data = o.optString("type") + "|" + o.optString("clinic_id") + "|" +
                    o.optString("device_id") + "|" + o.optString("nonce");
            byte[] expected = hmac(data).getBytes(StandardCharsets.US_ASCII);
            byte[] actual = o.optString("proof", "").getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) { return false; }
    }

    private static String hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pairKey(app).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject encrypt(String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        JSONObject o = new JSONObject();
        o.put("type", "secure");
        o.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        o.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return o;
    }

    private static JSONObject decrypt(JSONObject envelope) throws Exception {
        if (!"secure".equals(envelope.optString("type"))) throw new Exception("bad envelope");
        byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
        byte[] data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
        return new JSONObject(new String(cipher.doFinal(data), StandardCharsets.UTF_8));
    }

    private static SecretKeySpec aesKey() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(pairKey(app).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private static JSONObject cloneJson(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static String randomNonce() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) b.append(String.format(Locale.US, "%02x", value & 0xff));
        return b.toString();
    }

    private static void noteSuccess(String peerDevice) {
        prefs(app).edit()
                .putString("last_sync_at", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                .putString("last_peer", peerDevice == null || peerDevice.length() < 8 ? "جهاز العيادة" : "جهاز " + peerDevice.substring(0, 8))
                .remove("last_error").apply();
    }

    private static void noteError(String message) {
        Context c = app;
        if (c != null) prefs(c).edit().putString("last_error", message == null ? "" : message).apply();
    }

    private static void writeLine(BufferedWriter out, String value) throws Exception {
        out.write(value);
        out.write("\n");
        out.flush();
    }

    private static String readLine(BufferedReader in) throws Exception {
        String line = in.readLine();
        if (line == null || line.length() > 1_500_000) throw new Exception("connection closed");
        return line;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}

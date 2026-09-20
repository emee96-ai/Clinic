package com.eman.clinic;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends clinic subscription payment details to the platform admin queue. */
public final class BillingApi {
    private final Context context;
    private final AuthStore auth;

    public BillingApi(Context context) {
        this.context = context.getApplicationContext();
        this.auth = new AuthStore(this.context);
    }

    public void submitPayment(long amount, String method, String reference,
                              String plan, int days, String notes) throws Exception {
        if (!auth.hasRemoteIdentity()) throw new IOException("not_signed_in");
        if (amount <= 0) throw new IOException("invalid_amount");
        if (days < 1 || days > 730) throw new IOException("invalid_period");

        JSONObject body = new JSONObject();
        body.put("clinic_id", auth.clinicId());
        body.put("amount", amount);
        body.put("currency", "SDG");
        body.put("method", safe(method));
        body.put("reference", safe(reference));
        body.put("requested_plan", plan);
        body.put("requested_days", days);
        body.put("status", "pending");
        body.put("notes", safe(notes));
        body.put("submitted_by", auth.userId());

        Response r = post(body.toString());
        if (r.code == 401 && new SupabaseApi(context).refreshSession()) r = post(body.toString());
        if (r.code < 200 || r.code >= 300) throw new IOException(errorMessage(r));
    }

    private Response post(String body) throws IOException {
        URL url = new URL(SupabaseConfig.URL + "/rest/v1/subscription_payments");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY);
        c.setRequestProperty("Authorization", "Bearer " + auth.accessToken());
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Prefer", "return=minimal");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        c.disconnect();
        return new Response(code, text);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String errorMessage(Response r) {
        try {
            JSONObject j = new JSONObject(r.body);
            String m = j.optString("message", "");
            if (m.isEmpty()) m = j.optString("details", "");
            return m.isEmpty() ? "HTTP " + r.code : m;
        } catch (Exception e) {
            return "HTTP " + r.code;
        }
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
    }
}

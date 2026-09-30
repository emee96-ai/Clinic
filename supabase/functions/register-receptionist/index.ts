import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const jsonHeaders = { "Content-Type": "application/json" };

function response(status: number, message: string, extra: Record<string, unknown> = {}) {
  return new Response(JSON.stringify({ message, ...extra }), { status, headers: jsonHeaders });
}

async function sha256(value: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return response(405, "method_not_allowed");

  try {
    const { email, password, code, display_name } = await req.json();
    const cleanEmail = String(email ?? "").trim().toLowerCase();
    const cleanPassword = String(password ?? "");
    const cleanCode = String(code ?? "").trim().toUpperCase();
    const cleanName = String(display_name ?? "").trim();
    if (!cleanEmail.includes("@") || cleanPassword.length < 6 || cleanCode.length < 8 || cleanName.length < 2) {
      return response(400, "invalid_registration_data");
    }

    const url = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const admin = createClient(url, serviceKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    const hash = await sha256(cleanCode.replaceAll("-", ""));
    const { data: invite, error: inviteError } = await admin
      .from("clinic_invites")
      .select("id,role,active,expires_at,uses,max_uses")
      .eq("code_hash", hash)
      .maybeSingle();
    if (inviteError) return response(500, "invite_lookup_failed");
    if (!invite || !invite.active || invite.uses >= invite.max_uses || new Date(invite.expires_at) <= new Date()) {
      return response(404, "invite_invalid_or_expired");
    }
    if (invite.role !== "receptionist") {
      return response(422, "role_requires_email_confirmation");
    }

    const { data: created, error: createError } = await admin.auth.admin.createUser({
      email: cleanEmail,
      password: cleanPassword,
      email_confirm: true,
      user_metadata: { display_name: cleanName },
    });
    if (createError || !created.user) {
      const message = String(createError?.message ?? "account_create_failed").toLowerCase();
      return response(message.includes("already") || message.includes("exists") ? 409 : 400,
        message.includes("already") || message.includes("exists") ? "account_exists" : "account_create_failed");
    }

    const { error: membershipError } = await admin.rpc("service_register_receptionist", {
      p_user_id: created.user.id,
      p_code: cleanCode,
      p_display_name: cleanName,
    });
    if (membershipError) {
      await admin.auth.admin.deleteUser(created.user.id);
      const message = String(membershipError.message ?? "");
      return response(message.includes("invite_") ? 404 : 400,
        message.includes("invite_") ? "invite_invalid_or_expired" : "membership_create_failed");
    }

    return response(201, "receptionist_registered", { ok: true });
  } catch (_) {
    return response(400, "invalid_request");
  }
});

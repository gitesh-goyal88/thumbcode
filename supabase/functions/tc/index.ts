// One function with an internal router rather than four functions, so demo-day
// setup is a single `supabase functions deploy tc` and the portal and the app
// have one base URL to configure.
//
//   GET  /tc/health
//   POST /tc/issue    { payload, signature, signMode, credentialId }
//   POST /tc/verify   { docId, spots[], place }
//   POST /tc/revoke   { docId, reason }
//
// Registry listing and the scan log are not here: both are plain anon SELECTs
// against PostgREST, which also gives the portal Realtime on scans for free.

import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  canonicalJson,
  deriveSpots,
  maskReference,
  newDocId,
  sameSpots,
  sha256Hex,
} from "./spec.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

// The spot derivation key. Spots are derived from it rather than stored, so
// its value must never change: rotating it invalidates every code already
// printed. There is deliberately no fallback — a missing key must stop the
// function booting, not silently derive a different pattern.
//
//   supabase secrets set THUMBCODE_SPOT_SECRET=... --project-ref <ref>
const SECRET = Deno.env.get("THUMBCODE_SPOT_SECRET");
if (!SECRET) {
  throw new Error("Missing THUMBCODE_SPOT_SECRET environment variable");
}

const db = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

async function handleIssue(req: Request): Promise<Response> {
  const { payload, signature, signMode, credentialId } = await req.json();

  const required = ["docType", "holderName", "referenceNo", "officeCode", "officeName", "issueDate"];
  const missing = required.filter((f) => !payload?.[f]);
  if (missing.length) {
    return json({ error: `Missing: ${missing.join(", ")}` }, 400);
  }
  if (signMode === "webauthn" && !signature) {
    return json({ error: "Signed mode requires an assertion" }, 400);
  }

  const docId = newDocId();
  const spots = await deriveSpots(SECRET!, docId);
  const payloadHash = await sha256Hex(canonicalJson(payload));

  const { error: docErr } = await db.from("documents").insert({
    doc_id: docId,
    doc_type: payload.docType,
    office_code: payload.officeCode,
    office_name: payload.officeName,
    issue_date: payload.issueDate,
    reference_masked: maskReference(payload.referenceNo),
    payload_hash: payloadHash,
    sign_mode: signMode === "webauthn" ? "webauthn" : "demo",
    credential_id: credentialId ?? null,
    signature: signature ?? null,
  });
  if (docErr) return json({ error: docErr.message }, 500);

  // The fingerprint hash rides inside payload, so it is covered by the
  // operator's signature as well as stored in its own column. The image never
  // leaves the issuing machine.
  const { error: privErr } = await db.from("document_private").insert({
    doc_id: docId,
    holder_name: payload.holderName,
    reference_full: payload.referenceNo,
    holder_finger_hash: payload.holderFingerHash ?? null,
    finger_quality: payload.fingerQuality ?? null,
    payload,
  });
  if (privErr) {
    await db.from("documents").delete().eq("doc_id", docId);
    return json({ error: privErr.message }, 500);
  }

  return json({ docId, spots, payloadHash, issued: new Date().toISOString() });
}

async function handleVerify(req: Request): Promise<Response> {
  const { docId, spots, place } = await req.json();

  if (typeof docId !== "string" || !/^[0-9a-f]{10}$/.test(docId)) {
    return json({ error: "Malformed document id" }, 400);
  }
  const reported: number[] = Array.isArray(spots)
    ? spots.filter((s) => Number.isInteger(s) && s >= 0 && s < 32)
    : [];

  const { data: doc } = await db
    .from("documents")
    .select("*")
    .eq("doc_id", docId)
    .maybeSingle();

  let result: string;
  if (!doc) {
    result = "unknown";
  } else if (!sameSpots(reported, await deriveSpots(SECRET!, docId))) {
    result = "pattern_mismatch";
  } else if (doc.revoked) {
    result = "revoked";
  } else {
    result = "verified";
  }

  // Logged whatever the outcome. A forgery attempt is the most interesting row
  // in this table, so it must not be the one we drop.
  await db.from("scans").insert({
    doc_id: docId,
    result,
    spots: reported,
    place: typeof place === "string" ? place.slice(0, 120) : null,
  });

  if (result === "unknown") {
    return json({ result, docId });
  }
  if (result === "pattern_mismatch") {
    return json({ result, docId, expectedCount: null });
  }

  // Fetch private details so the scanning officer sees the full picture.
  // This runs under the service role, which bypasses the zero-policy RLS
  // on document_private.
  const { data: priv } = await db
    .from("document_private")
    .select("holder_name, reference_full, payload")
    .eq("doc_id", docId)
    .maybeSingle();

  return json({
    result,
    docId,
    docType: doc.doc_type,
    officeCode: doc.office_code,
    officeName: doc.office_name,
    issueDate: doc.issue_date,
    reference: doc.reference_masked,
    holderName: priv?.holder_name ?? null,
    referenceNo: priv?.reference_full ?? null,
    particulars: priv?.payload?.particulars ?? null,
    signMode: doc.sign_mode,
    revokedReason: doc.revoked ? doc.revoked_reason : null,
    revokedAt: doc.revoked ? doc.revoked_at : null,
  });
}

async function handleRevoke(req: Request): Promise<Response> {
  const { docId, reason } = await req.json();
  const { data, error } = await db
    .from("documents")
    .update({
      revoked: true,
      revoked_reason: reason ?? "No reason recorded",
      revoked_at: new Date().toISOString(),
    })
    .eq("doc_id", docId)
    .select("doc_id")
    .maybeSingle();

  if (error) return json({ error: error.message }, 500);
  if (!data) return json({ error: "No such document" }, 404);
  return json({ docId, revoked: true });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });

  const route = new URL(req.url).pathname.split("/").filter(Boolean).pop();

  if (route === "health") {
    return json({ ok: true, secret: Boolean(SECRET), time: new Date().toISOString() });
  }
  try {
    if (req.method === "POST" && route === "issue") return await handleIssue(req);
    if (req.method === "POST" && route === "verify") return await handleVerify(req);
    if (req.method === "POST" && route === "revoke") return await handleRevoke(req);
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
  return json({ error: "Unknown route" }, 404);
});

// ThumbCode canonical spec — server copy.
//
// This file, the <script> block in portal/index.html, and ThumbCodeSpec.kt in
// the Android app must agree exactly. If you change a constant here, change it
// in the other two in the same commit or nothing decodes.

export const CANVAS = { w: 400, h: 480 };
export const OVAL = { cx: 200, cy: 215, rx: 155, ry: 180 };
export const RING_FACTOR = 0.90;
export const RING_SLOTS = 64;
export const SYNC = [1, 1, 0, 1, 0, 0, 1, 1];
export const ID_BYTES = 5;
export const SPOT_MIN_SEPARATION = 30;
export const SPOT_MAX = 10;

export const RIDGE_FACTORS = [0.20, 0.38, 0.56, 0.72];

const ANCHOR_RINGS = [
  { factor: 0.28, count: 8, phase: 0 },
  { factor: 0.52, count: 12, phase: 0.5 },
  { factor: 0.74, count: 12, phase: 0 },
];

export interface Point { x: number; y: number }

// Order matters: the index is what travels over the wire, so this list is
// part of the protocol, not an implementation detail.
export const ANCHORS: Point[] = (() => {
  const out: Point[] = [];
  for (const ring of ANCHOR_RINGS) {
    for (let i = 0; i < ring.count; i++) {
      const t = (2 * Math.PI * (i + ring.phase)) / ring.count - Math.PI / 2;
      out.push({
        x: OVAL.cx + OVAL.rx * ring.factor * Math.cos(t),
        y: OVAL.cy + OVAL.ry * ring.factor * Math.sin(t),
      });
    }
  }
  return out;
})();

export const ANCHOR_COUNT = ANCHORS.length; // 32

export function crc16(bytes: Uint8Array): number {
  let crc = 0xffff;
  for (const b of bytes) {
    crc ^= b << 8;
    for (let i = 0; i < 8; i++) {
      crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) : (crc << 1);
      crc &= 0xffff;
    }
  }
  return crc;
}

export function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return out;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// 64 bits: sync, then the 5 id bytes MSB first, then CRC-16 over those bytes.
export function ringBits(docId: string): number[] {
  const id = hexToBytes(docId);
  const bits: number[] = [...SYNC];
  for (const b of id) {
    for (let i = 7; i >= 0; i--) bits.push((b >> i) & 1);
  }
  const crc = crc16(id);
  for (let i = 15; i >= 0; i--) bits.push((crc >> i) & 1);
  return bits;
}

export function newDocId(): string {
  return bytesToHex(crypto.getRandomValues(new Uint8Array(ID_BYTES)));
}

async function hmac(secret: string, message: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(message),
  );
  return new Uint8Array(sig);
}

// Deterministic from the document id under the server key. Two passes: the
// first enforces minimum spacing so adjacent spots never merge into one blob
// under toner spread, the second drops that rule so a high k is always
// reachable.
export async function deriveSpots(secret: string, docId: string): Promise<number[]> {
  const parts: Uint8Array[] = [];
  for (let counter = 0; counter < 3; counter++) {
    parts.push(await hmac(secret, `thumbcode-spots:${docId}|${counter}`));
  }
  const bytes = new Uint8Array(96);
  parts.forEach((p, i) => bytes.set(p, i * 32));

  const k = 1 + (bytes[0] % SPOT_MAX);
  const chosen: number[] = [];

  for (let pass = 0; pass < 2; pass++) {
    for (let i = 1; i < bytes.length && chosen.length < k; i++) {
      const idx = bytes[i] % ANCHOR_COUNT;
      if (chosen.includes(idx)) continue;
      if (pass === 0) {
        const a = ANCHORS[idx];
        const tooClose = chosen.some((c) => {
          const b = ANCHORS[c];
          return Math.hypot(a.x - b.x, a.y - b.y) < SPOT_MIN_SEPARATION;
        });
        if (tooClose) continue;
      }
      chosen.push(idx);
    }
  }
  return chosen.sort((a, b) => a - b);
}

export function sameSpots(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false;
  const x = [...a].sort((m, n) => m - n);
  const y = [...b].sort((m, n) => m - n);
  return x.every((v, i) => v === y[i]);
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`).join(",")}}`;
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return bytesToHex(new Uint8Array(digest));
}

export function maskReference(ref: string): string {
  const trimmed = ref.trim();
  if (trimmed.length <= 4) return "•".repeat(trimmed.length);
  return "•".repeat(trimmed.length - 4) + trimmed.slice(-4);
}

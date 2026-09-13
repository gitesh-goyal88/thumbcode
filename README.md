# ThumbCode

Three components. This drop contains the first two.

```
supabase/migrations/0001_init.sql     schema, RLS, realtime
supabase/functions/tc/spec.ts         canonical geometry + spot derivation
supabase/functions/tc/index.ts        issue / verify / revoke / health
portal/index.html                     issuing portal, no build step
```

## Setup

Live on project `iofwmndnclsfhifqspcs` (region ap-southeast-2): schema, RLS,
Realtime, the `scan_anomalies` view, and the `tc` edge function at version 2.

Both clients ship pointed at it, so neither needs setting up before first
use. The portal falls back to `DEFAULTS` at the top of its script block; the
app falls back to the constants in `Settings`. Whatever you type on either
settings screen overrides the default and persists locally.

The anon key sits in both files in the clear. That is what it is for - it
grants nothing beyond what `/verify` already hands to anyone pointing a camera
at a printed code, and RLS is what protects the holder names. The derivation
key is the one that must not leak; see below.

Serve `portal/index.html` over `localhost` or https - WebAuthn is refused on
`file://`, and the portal will say so and fall back to demo mode.

### The derivation key is currently in the function source

Supabase's management API has no endpoint for setting function secrets, so
`SECRET` in `functions/tc/index.ts` falls back to a literal. It still reads
`THUMBCODE_SPOT_SECRET` first, so the fix is one command and a redeploy:

```bash
supabase link --project-ref iofwmndnclsfhifqspcs
supabase secrets set THUMBCODE_SPOT_SECRET=34d6e30c56f7717c284a7a4f87720ab58cdfcaaff96608213f507b087a9bbcb5
```

Then replace the literal with a `throw`. Do this before the repository goes
public - the key in that file is enough to draw a code that verifies. Do not
change the value: every code already issued derives from it.

### Verified against the live project

Exercised over HTTP end to end, calling the deployed function from Postgres
via `pg_net`:

| Case | Result |
| --- | --- |
| issue a land record extract | `5140dc5098`, 9 spots |
| verify with the derived spots | `verified`, no holder name, reference masked to the last four |
| verify with one spot index changed | `pattern_mismatch` |
| verify a document id never issued | `unknown` |
| revoke, then verify again | `revoked`, with the reason |
| two verified scans, different places | `scan_anomalies` flags the document |
| portal's registry, scan log and anomaly reads over REST with the anon key | all 200, correct rows |
| `document_private` over REST with the anon key | empty, so RLS holds at the API layer too |

Also checked: the `anon` role reads `documents` and gets nothing from
`document_private`; pgcrypto's `hmac()` and the function's WebCrypto agree byte
for byte; the local Node implementation derives the same spot pattern the
deployed function returned for `5140dc5098`, which is the three-way spec
agreement the whole design rests on.

The linter's "RLS enabled, no policy" on `document_private` is INFO level and
is the intended state, not a finding to fix.

### What is in the database right now

- `5140dc5098` - land record extract, revoked, four scans against it
- `93d3a15dcc` - encumbrance certificate, valid, printable code included in
  this drop as `thumbcode-93d3a15dcc.svg` at 55 mm

`pg_net` was enabled for that testing and is still installed. Only the service
role can reach it; drop it with `drop extension pg_net;` if you would rather it
were not there.

## The ridge field

Each document gets its own ridge pattern - loop, whorl or arch - with its own
core position, ridge spacing, wobble and ridge endings, all derived from the
document id through a plain non-secret hash. Two documents never look alike,
and any renderer reproduces a given code exactly.

When a fingerprint is captured at issue, **the print itself goes on the code**.
The frame is binarised with Otsu on the issuing machine, clipped to an ellipse
at 0.82 of the oval radii, and embedded in the SVG. Capture happens once; the
printed code is what gets scanned from then on and the finger is never
presented again. With no reader attached the code falls back to a ridge pattern
generated from the document id, and nothing else changes.

Only the SHA-256 of the ISO template is sent to the server. The image never
leaves the issuing machine - it goes onto the paper and into the browser's
memory, nowhere else.

Two constraints make the real print safe to decode, and both matter:

- the image is clipped at 0.82 of the oval, so it never reaches the data ring
  at 0.90. Real ridges are much denser than drawn ones and would otherwise sit
  under the ring slots.
- every one of the 32 anchor positions is punched white (radius 9) before the
  spots are drawn. Without that the decoder samples ridge ink at an unset
  anchor and reads a spot that was never there. This is the one failure mode a
  real print introduces, and it looks exactly like a forgery when it happens.

Uniqueness does not come from any of this. The document id is 5 random bytes
and the spots are a keyed hash of it, so codes are already unique by
construction. The print is what binds a document to a person and makes that
uniqueness visible.

One thing to weigh before this goes to a real office: a document that gets
photocopied at every counter now carries a reproduction of someone's ridges,
and a fingerprint cannot be reissued after it leaks. The generated-pattern
fallback is still in the code if that trade ever needs reversing - it is one
argument at the `buildSvg` call site.

One thing to watch on the first real scan: ridges now cross the anchor
positions where before there were only four sparse ellipses. Stroke width is
0.9 units, which should leave the anchor samples comfortably above threshold,
but if spots start reading as set when they are not, thinning the ridges is the
first thing to try.

## Where the trust sits

Spot patterns are never stored. `verify` recomputes them from the document id
under `THUMBCODE_SPOT_SECRET` on every call. Someone who dumps the entire
database still cannot draw a code that verifies.

`documents` is anon-readable because it holds nothing a stranger with a camera
should not learn. The holder name and full reference live in
`document_private`, which has RLS on and no policy at all, so only the service
role inside the edge function can read it. Do not add a policy to that table.

## Verifying the three copies of the spec agree

`spec.ts`, the `<script>` block in `portal/index.html`, and the forthcoming
`ThumbCodeSpec.kt` all restate the same geometry. Known-good values to check
any port against:

- CRC-16/CCITT over ASCII `123456789` is `0x29B1`
- document id `a3f9c1d0e7` produces the 64 bits
  `1101001110100011111110011100000111010000111001110011000010101001`
- 32 anchors, minimum centre-to-centre separation 34.05 units

## Component 3 — receiver app

```
android/app/src/main/java/in/thumbcode/receiver/
  ThumbCodeSpec.kt   third copy of the canonical geometry
  Decoder.kt         OpenCV pipeline, frame in / Frame out
  VerifyClient.kt    settings store + POST to /tc/verify
  MainActivity.kt    OpenCV init, CameraX, verification handoff
  ui/CanvasOverlay.kt  the live canonical canvas
  ui/ScanScreen.kt     scan screen, verdicts, settings
```

There is no Gradle wrapper in this drop because it has to be generated with a
network. Open `android/` in Android Studio and let it create one, or run
`gradle wrapper` once.

On first launch the app opens straight into settings: project URL, anon key,
and a place label. The place label is not cosmetic — it is the field the
`scan_anomalies` view groups on, so leave it meaningful.

### Reading the instrument

The canvas redraws what the decoder actually measured, not what it expects:

- grey fiducial squares mean no lock; blue means four corners found
- ring slots stay grey until the ring is sampled, then large green for a set
  bit and small grey for a clear one
- anchors are hollow outlines until sampled; a filled amber disc is a spot
- the wedge strip is painted with the four measured values, so a washed-out
  strip tells you the illumination flattening is losing

If the ring reads as a solid arc, the print is too small. If the spots land
between anchors, the corner ordering is wrong and the warp is mirrored.

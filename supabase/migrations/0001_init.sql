-- ThumbCode: registry, private holder data, scan log.
--
-- Split is deliberate. `documents` carries nothing a stranger with a camera
-- shouldn't learn, so anon can read it directly and Realtime works without a
-- proxy. `document_private` carries the holder name and the full reference and
-- has no policies at all, so only the service role (the edge function) sees it.
--
-- Spot patterns are NOT stored anywhere. They are recomputed from the document
-- id under THUMBCODE_SPOT_SECRET every time. Nothing in the database is
-- sufficient to forge a code.

create extension if not exists pgcrypto;

create table public.documents (
  doc_id            text primary key
                    check (doc_id ~ '^[0-9a-f]{10}$'),
  doc_type          text        not null,
  office_code       text        not null,
  office_name       text        not null,
  issue_date        date        not null,
  reference_masked  text        not null,
  payload_hash      text        not null,
  sign_mode         text        not null default 'demo'
                    check (sign_mode in ('webauthn', 'demo')),
  credential_id     text,
  signature         text,
  revoked           boolean     not null default false,
  revoked_reason    text,
  revoked_at        timestamptz,
  issued_at         timestamptz not null default now()
);

create table public.document_private (
  doc_id          text primary key references public.documents(doc_id) on delete cascade,
  holder_name     text not null,
  reference_full  text not null,
  payload         jsonb not null
);

create table public.scans (
  id          bigint generated always as identity primary key,
  doc_id      text not null,
  result      text not null
              check (result in ('verified', 'revoked', 'unknown', 'pattern_mismatch')),
  spots       smallint[] not null default '{}',
  place       text,
  scanned_at  timestamptz not null default now()
);

create index scans_doc_id_idx on public.scans (doc_id, scanned_at desc);
create index scans_recent_idx on public.scans (scanned_at desc);
create index documents_issued_idx on public.documents (issued_at desc);

alter table public.documents       enable row level security;
alter table public.document_private enable row level security;
alter table public.scans           enable row level security;

-- Readable by anyone: this is the same information /verify hands back to any
-- phone that scans the paper.
create policy documents_public_read on public.documents
  for select to anon, authenticated using (true);

create policy scans_public_read on public.scans
  for select to anon, authenticated using (true);

-- document_private gets no policy on purpose. With RLS on and no policy, every
-- role except service_role is denied. Do not add one.

-- No insert/update/delete policies anywhere: all writes go through the edge
-- function, which uses the service role key and bypasses RLS.

alter publication supabase_realtime add table public.scans;

-- Same-document-two-places detection. The brief is honest that visual
-- anti-copy layers lose to a good scanner; this is the thing that actually
-- catches a duplicated certificate.
create or replace view public.scan_anomalies
with (security_invoker = true) as
select
  doc_id,
  count(*)                        as scan_count,
  count(distinct place)           as place_count,
  min(scanned_at)                 as first_seen,
  max(scanned_at)                 as last_seen
from public.scans
where result = 'verified'
  and scanned_at > now() - interval '24 hours'
group by doc_id
having count(distinct place) > 1;

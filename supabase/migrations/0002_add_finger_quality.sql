alter table public.document_private
  add column holder_finger_hash text,
  add column finger_quality smallint;

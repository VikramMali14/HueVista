-- Drop the external support-channel plumbing.
--
-- The WhatsApp (Meta Cloud API) and voice (ElevenLabs) adapters were never
-- implemented — no account, no credentials, no traffic — so every conversation
-- that exists is IN_APP and both contact columns are unconditionally NULL. The
-- adapters and their config are gone from the application; this brings the
-- schema in line.
--
-- contact_channel_id / contact_name only ever held the phone number or profile
-- name of an external contact arriving over one of those adapters.
ALTER TABLE support_conversations DROP COLUMN IF EXISTS contact_channel_id;
ALTER TABLE support_conversations DROP COLUMN IF EXISTS contact_name;

-- Narrow the channel CHECK to the values the enum still has. Any legacy row on a
-- removed channel is remapped first so the constraint can be applied — in
-- practice there are none, since nothing could ever write one.
UPDATE support_conversations SET channel = 'IN_APP' WHERE channel <> 'IN_APP';

ALTER TABLE support_conversations DROP CONSTRAINT IF EXISTS support_conversations_channel_check;
ALTER TABLE support_conversations
    ADD CONSTRAINT support_conversations_channel_check CHECK (channel::text = 'IN_APP');

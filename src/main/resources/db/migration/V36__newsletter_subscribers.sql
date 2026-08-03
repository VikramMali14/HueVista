-- The monthly-letter list.
--
-- The journal sign-up form used to be a no-op: it set "Thank you" in the browser
-- and discarded the address, so there was no list and nobody ever received the
-- letter they asked for. This is where an address actually lands.
--
-- One row per address, ever. Re-subscribing after an unsubscribe flips the row
-- back rather than inserting a second one, so a resubscribe can never produce two
-- copies of the same send; the unique index on email is what enforces that.
CREATE TABLE newsletter_subscribers (
    id character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    status character varying(20) NOT NULL,
    source character varying(60),
    -- The secret in the unsubscribe link: random per row, so leaving needs no
    -- login and knowing an address is not enough to remove it.
    unsubscribe_token character varying(64) NOT NULL,
    welcomed_at timestamp(6) without time zone,
    unsubscribed_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone
);

ALTER TABLE ONLY newsletter_subscribers
    ADD CONSTRAINT newsletter_subscribers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY newsletter_subscribers
    ADD CONSTRAINT newsletter_subscribers_email_key UNIQUE (email);

ALTER TABLE ONLY newsletter_subscribers
    ADD CONSTRAINT newsletter_subscribers_token_key UNIQUE (unsubscribe_token);

CREATE INDEX idx_newsletter_subscribers_status ON newsletter_subscribers (status);

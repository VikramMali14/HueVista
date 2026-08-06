-- Every trip through a Razorpay Checkout, paid or not.
--
-- Every other billing table records a payment that SUCCEEDED — subscription_payments,
-- points_purchases, project_purchases, store_payments all exist because a signature
-- verified. So the most common thing that happens at a checkout, someone opening it and
-- not paying, has never left a trace anywhere except an application log line that has
-- since rotated away. When a shop says "I paid and nothing happened", or a week's revenue
-- halves because a button broke on one page, there is nothing to read.
--
-- A row here is opened when the order or subscription is created — strictly before the
-- buyer could have paid — and then moved along as the browser reports what happened to
-- it. The columns are chosen for one job: making a past incident reconstructable without
-- the logs. The page the buyer clicked Pay on, their IP and browser, the amount they were
-- quoted, the gateway's own error code, and a timestamp on every step.
--
-- Nothing in here grants anything. The real purchase tables still decide what a buyer
-- gets; this table only ever explains. That separation is deliberate — a browser reports
-- some of these fields, so a hostile client can write a misleading row, and it must never
-- be able to write itself a plan. See PaymentAttemptService for what is and isn't trusted.
CREATE TABLE payment_attempts (
    -- 255 like every other UUID primary key here, so schema validation on start-up
    -- compares like with like.
    id character varying(255) NOT NULL,

    -- The Razorpay id this attempt is keyed by: order_… for the one-off flows, sub_… for
    -- a subscription. UNIQUE because it is also the handle the browser quotes when it
    -- reports an event, and two rows sharing it would make those reports ambiguous —
    -- which is also, conveniently, what stops a client inventing new rows at will.
    reference character varying(255) NOT NULL,

    -- SUBSCRIPTION | POINTS | PROJECT | REOPEN | STORE_KIOSK
    flow character varying(32) NOT NULL,
    -- CREATED | OPENED | ABANDONED | FAILED | VERIFY_FAILED | PAID
    status character varying(32) NOT NULL,

    -- Null for a walk-in kiosk customer, who has no account.
    user_id character varying(255),
    -- Denormalized deliberately: an attempt is most useful precisely when the account has
    -- since been deleted, and a dangling id would name nobody.
    user_email character varying(255),
    -- The shop, for a kiosk sale — there is no buyer account to attribute that one to.
    organization_id character varying(255),

    amount_paise integer DEFAULT 0 NOT NULL,
    currency character varying(8) DEFAULT 'INR',
    description character varying(200),
    plan character varying(32),
    payment_id character varying(255),

    -- Where the buyer actually was. page_url is the point of the whole table: /plan,
    -- /pricing, the quota wall inside the visualizer and a store kiosk all open a
    -- Checkout, and nothing recorded until now could tell those apart afterwards.
    page_url character varying(1024),
    referrer character varying(1024),
    user_agent character varying(512),
    ip_address character varying(64),

    -- The gateway's account of what went wrong, kept in its own fields...
    error_code character varying(64),
    error_description character varying(512),
    error_source character varying(64),
    error_step character varying(64),
    error_reason character varying(128),
    -- ...and ours kept separately, so it is always obvious which side of the wire
    -- produced the complaint.
    failure_note text,

    -- Every transition, one per line, oldest first. A status column can only say where an
    -- attempt ended up; support questions are nearly always about the path it took.
    timeline text,

    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone,
    -- Null = the buyer never saw a payment window at all, which is a different failure
    -- from closing one.
    opened_at timestamp(6) without time zone,
    closed_at timestamp(6) without time zone,

    CONSTRAINT payment_attempts_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_attempt_reference ON payment_attempts (reference);

-- The report is always "newest first, narrowed by one thing", so each filter gets its own
-- index rather than a composite that only serves one filter order.
CREATE INDEX idx_attempt_user ON payment_attempts (user_id);
CREATE INDEX idx_attempt_status ON payment_attempts (status);
CREATE INDEX idx_attempt_flow ON payment_attempts (flow);
CREATE INDEX idx_attempt_created ON payment_attempts (created_at);

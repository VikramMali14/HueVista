-- Subscription flow: close the checkout-verify replay, and stop an upgrade from
-- destroying credits the shop already paid for.

-- 1. Claim each Razorpay subscription payment exactly once.
--
--    The subscription signature is HMAC(payment_id|subscription_id, secret): no nonce,
--    no timestamp, valid forever. /api/billing/subscriptions/verify activated any
--    non-ACTIVE row, so a shop that kept its first successful Checkout payload could
--    re-POST it every time the plan lapsed and get another month for nothing. The
--    primary key is the claim — the same guard points_purchases already gives top-ups.
CREATE TABLE subscription_payments (
    payment_id               varchar(255) PRIMARY KEY,
    razorpay_subscription_id varchar(255) NOT NULL,
    subscription_id          varchar(255) NOT NULL,
    user_id                  varchar(255) NOT NULL,
    created_at               timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscription_payments_subscription
    ON subscription_payments (razorpay_subscription_id);

-- 2. How many billing cycles this subscription has actually been charged for.
--
--    handlePaymentCaptured told a first charge from a renewal by asking whether the
--    period had started more than 7 days ago. That guess breaks outright once a plan
--    can be scheduled to start at a later date (see 3), and it already misfired when a
--    late subscription.activated echo reset current_period_start. Counting the charges
--    answers the question exactly.
ALTER TABLE subscriptions
    ADD COLUMN billing_cycles_charged integer NOT NULL DEFAULT 0;

-- Existing live paid rows have been charged at least once; trials and unpaid attempts
-- have not. Without the backfill every one of them would read as a first charge on its
-- next renewal and send an "activated" email instead of a receipt.
UPDATE subscriptions
   SET billing_cycles_charged = 1
 WHERE trial = false
   AND razorpay_subscription_id IS NOT NULL
   AND status IN ('ACTIVE', 'CANCELLED', 'HALTED', 'COMPLETED');

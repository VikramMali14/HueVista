-- "Send to my shop": timestamp set when the customer explicitly hands their
-- finished project to the issuing shop. Null until they do.
ALTER TABLE projects ADD COLUMN sent_to_shop_at timestamp(6) without time zone;

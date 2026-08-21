--liquibase formatted sql

--changeset codex:004-add-payment-type
ALTER TABLE games
    ADD COLUMN payment_type VARCHAR(20);

UPDATE games
SET payment_type = 'POSTPAYMENT'
WHERE cost_type = 'PAID';

ALTER TABLE games
    ADD CONSTRAINT ck_games_payment_type CHECK (
        (cost_type = 'PAID' AND payment_type IN ('PREPAYMENT', 'POSTPAYMENT'))
        OR (cost_type IS DISTINCT FROM 'PAID' AND payment_type IS NULL)
    );

--rollback ALTER TABLE games DROP COLUMN payment_type;

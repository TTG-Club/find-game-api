--liquibase formatted sql

--changeset codex:003-add-duration-and-cost
ALTER TABLE games
    ADD COLUMN duration_type VARCHAR(20) NOT NULL DEFAULT 'CAMPAIGN',
    ADD COLUMN cost_type VARCHAR(20),
    ADD COLUMN price_amount NUMERIC(12, 2),
    ADD COLUMN price_currency VARCHAR(3);

ALTER TABLE games
    ALTER COLUMN duration_type DROP DEFAULT,
    ADD CONSTRAINT ck_games_duration_type CHECK (duration_type IN ('ONE_SHOT', 'CAMPAIGN')),
    ADD CONSTRAINT ck_games_cost CHECK (
        (cost_type IS NULL AND price_amount IS NULL AND price_currency IS NULL)
        OR (cost_type = 'FREE' AND price_amount IS NULL AND price_currency IS NULL)
        OR (cost_type = 'PAID' AND price_amount > 0 AND price_currency ~ '^[A-Z]{3}$')
    );

--rollback ALTER TABLE games DROP COLUMN price_currency, DROP COLUMN price_amount, DROP COLUMN cost_type, DROP COLUMN duration_type;

--liquibase formatted sql

--changeset codex:009-move-cost-to-game-sessions
ALTER TABLE game_sessions
    ADD COLUMN price_amount NUMERIC(12, 2),
    ADD COLUMN price_currency VARCHAR(3),
    ADD COLUMN payment_type VARCHAR(20);

UPDATE game_sessions session
SET price_amount = game.price_amount,
    price_currency = game.price_currency,
    payment_type = game.payment_type
FROM games game
WHERE session.game_id = game.id
  AND game.cost_type = 'PAID';

ALTER TABLE game_sessions
    ADD CONSTRAINT ck_game_sessions_cost CHECK (
        (price_amount IS NULL AND price_currency IS NULL AND payment_type IS NULL)
        OR (price_amount IS NOT NULL
            AND price_currency IS NOT NULL
            AND payment_type IS NOT NULL
            AND price_amount > 0
            AND price_currency ~ '^[A-Z]{3}$'
            AND payment_type IN ('PREPAYMENT', 'POSTPAYMENT'))
    );

ALTER TABLE games
    DROP CONSTRAINT ck_games_payment_type,
    DROP CONSTRAINT ck_games_cost;

UPDATE games
SET cost_type = 'FREE'
WHERE cost_type IS NULL;

ALTER TABLE games
    ALTER COLUMN cost_type SET NOT NULL,
    DROP COLUMN price_amount,
    DROP COLUMN price_currency,
    DROP COLUMN payment_type,
    ADD CONSTRAINT ck_games_cost_type CHECK (cost_type IN ('FREE', 'PAID'));

--rollback ALTER TABLE games DROP CONSTRAINT ck_games_cost_type;
--rollback ALTER TABLE games ALTER COLUMN cost_type DROP NOT NULL;
--rollback ALTER TABLE games ADD COLUMN price_amount NUMERIC(12, 2), ADD COLUMN price_currency VARCHAR(3), ADD COLUMN payment_type VARCHAR(20);
--rollback UPDATE games game SET price_amount = session.price_amount, price_currency = session.price_currency, payment_type = session.payment_type FROM game_sessions session WHERE session.game_id = game.id AND game.cost_type = 'PAID';
--rollback ALTER TABLE games ADD CONSTRAINT ck_games_cost CHECK ((cost_type = 'FREE' AND price_amount IS NULL AND price_currency IS NULL) OR (cost_type = 'PAID' AND price_amount > 0 AND price_currency ~ '^[A-Z]{3}$'));
--rollback ALTER TABLE games ADD CONSTRAINT ck_games_payment_type CHECK ((cost_type = 'PAID' AND payment_type IN ('PREPAYMENT', 'POSTPAYMENT')) OR (cost_type IS DISTINCT FROM 'PAID' AND payment_type IS NULL));
--rollback ALTER TABLE game_sessions DROP CONSTRAINT ck_game_sessions_cost, DROP COLUMN price_amount, DROP COLUMN price_currency, DROP COLUMN payment_type;

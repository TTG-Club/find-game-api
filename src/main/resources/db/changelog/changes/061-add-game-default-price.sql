--liquibase formatted sql

--changeset ttg:061-add-game-default-price
-- Стоимость сессии по умолчанию: игрок видит цену платной игры ещё до того,
-- как мастер назначил первую встречу, а новые сессии берут её, если своей нет.
-- Игры, заведённые раньше, остаются без цены — клиент показывает «Не указано»,
-- пока мастер её не задаст.
ALTER TABLE games
    ADD COLUMN price_amount NUMERIC(12, 2),
    ADD COLUMN price_currency VARCHAR(3),
    ADD COLUMN payment_type VARCHAR(20);

ALTER TABLE games
    ADD CONSTRAINT ck_games_default_price CHECK (
        (price_amount IS NULL AND price_currency IS NULL AND payment_type IS NULL)
        OR (cost_type = 'PAID'
            AND price_amount > 0
            AND price_currency ~ '^[A-Z]{3}$'
            AND payment_type IN ('PREPAYMENT', 'POSTPAYMENT'))
    );

--rollback ALTER TABLE games DROP CONSTRAINT ck_games_default_price, DROP COLUMN price_amount, DROP COLUMN price_currency, DROP COLUMN payment_type;

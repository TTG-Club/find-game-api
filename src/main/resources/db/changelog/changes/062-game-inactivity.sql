--liquibase formatted sql

--changeset ttg:062-game-inactivity
-- Брошенные игры: последняя активность мастера и срок, до которого он может
-- подтвердить, что игра ещё актуальна. Без ответа игра отменяется сама.
ALTER TABLE games
    ADD COLUMN last_activity_at TIMESTAMPTZ,
    ADD COLUMN auto_cancel_at TIMESTAMPTZ;

-- Для уже заведённых игр активность собирается из того, что о мастере уже
-- известно: правки объявления, решения по заявкам и прошедшие встречи.
UPDATE games game
SET last_activity_at = GREATEST(
        game.updated_at,
        (SELECT MAX(registration.updated_at)
         FROM game_registrations registration
         WHERE registration.game_id = game.id
           AND registration.status IN ('APPROVED', 'REJECTED')),
        (SELECT MAX(COALESCE(session.completed_at, session.starts_at))
         FROM game_sessions session
         WHERE session.game_id = game.id
           AND COALESCE(session.completed_at, session.starts_at) <= NOW()));

ALTER TABLE games
    ALTER COLUMN last_activity_at SET NOT NULL;

-- Планировщик ищет открытые игры по давности активности и по сроку отмены.
CREATE INDEX ix_games_open_last_activity
    ON games (last_activity_at)
    WHERE status = 'OPEN' AND deleted_at IS NULL AND auto_cancel_at IS NULL;

CREATE INDEX ix_games_open_auto_cancel
    ON games (auto_cancel_at)
    WHERE status = 'OPEN' AND deleted_at IS NULL AND auto_cancel_at IS NOT NULL;

--rollback DROP INDEX ix_games_open_auto_cancel; DROP INDEX ix_games_open_last_activity; ALTER TABLE games DROP COLUMN auto_cancel_at, DROP COLUMN last_activity_at;

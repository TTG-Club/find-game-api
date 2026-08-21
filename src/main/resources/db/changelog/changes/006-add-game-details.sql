--liquibase formatted sql

--changeset codex:006-add-game-details
ALTER TABLE games
    ADD COLUMN city VARCHAR(120),
    ADD COLUMN min_age INTEGER,
    ADD COLUMN max_age INTEGER,
    ADD COLUMN starting_level INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE games
    ALTER COLUMN starting_level DROP DEFAULT,
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ADD CONSTRAINT ck_games_city CHECK (
        city IS NULL OR (game_type = 'OFFLINE' AND LENGTH(BTRIM(city)) > 0)
    ),
    ADD CONSTRAINT ck_games_age_range CHECK (
        (min_age IS NULL AND max_age IS NULL)
        OR (min_age BETWEEN 0 AND 120 AND max_age BETWEEN 0 AND 120 AND min_age <= max_age)
    ),
    ADD CONSTRAINT ck_games_starting_level CHECK (starting_level BETWEEN 1 AND 20),
    ADD CONSTRAINT ck_games_status CHECK (status IN ('DRAFT', 'PENDING_MODERATION', 'OPEN', 'CLOSED'));

--rollback ALTER TABLE games DROP COLUMN updated_at, DROP COLUMN status, DROP COLUMN starting_level, DROP COLUMN max_age, DROP COLUMN min_age, DROP COLUMN city;

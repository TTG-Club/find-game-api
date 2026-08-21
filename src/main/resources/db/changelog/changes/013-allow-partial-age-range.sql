--liquibase formatted sql

--changeset codex:013-allow-partial-age-range
ALTER TABLE games
    DROP CONSTRAINT ck_games_age_range,
    ADD CONSTRAINT ck_games_age_range CHECK (
        (min_age IS NULL OR min_age BETWEEN 0 AND 120)
        AND (max_age IS NULL OR max_age BETWEEN 0 AND 120)
        AND (min_age IS NULL OR max_age IS NULL OR min_age <= max_age)
    );

--rollback ALTER TABLE games DROP CONSTRAINT ck_games_age_range;
--rollback ALTER TABLE games ADD CONSTRAINT ck_games_age_range CHECK ((min_age IS NULL AND max_age IS NULL) OR (min_age BETWEEN 0 AND 120 AND max_age BETWEEN 0 AND 120 AND min_age <= max_age));

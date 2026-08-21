--liquibase formatted sql

--changeset codex:018-add-session-estimated-duration
ALTER TABLE game_sessions
    ADD COLUMN estimated_duration_minutes INTEGER,
    ADD CONSTRAINT ck_game_sessions_estimated_duration CHECK (
        estimated_duration_minutes IS NULL OR estimated_duration_minutes > 0
    );

--rollback ALTER TABLE game_sessions DROP CONSTRAINT ck_game_sessions_estimated_duration, DROP COLUMN estimated_duration_minutes;

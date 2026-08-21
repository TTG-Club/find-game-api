--liquibase formatted sql

--changeset codex:016-add-game-deletion-reason
ALTER TABLE games
    ADD COLUMN deletion_reason VARCHAR(1000),
    ADD CONSTRAINT ck_games_deletion_reason CHECK (
        deletion_reason IS NULL OR LENGTH(BTRIM(deletion_reason)) > 0
    );

--rollback ALTER TABLE games DROP CONSTRAINT ck_games_deletion_reason, DROP COLUMN deletion_reason;

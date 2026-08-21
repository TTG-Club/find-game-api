--liquibase formatted sql

--changeset codex:012-add-crossplay-allowed
ALTER TABLE games
    ADD COLUMN crossplay_allowed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE games
    ALTER COLUMN crossplay_allowed DROP DEFAULT;

--rollback ALTER TABLE games DROP COLUMN crossplay_allowed;

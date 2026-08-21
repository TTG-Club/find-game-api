--liquibase formatted sql

--changeset codex:011-enable-post-moderation
UPDATE games
SET status = 'OPEN'
WHERE status IN ('DRAFT', 'PENDING_MODERATION');

ALTER TABLE games
    DROP CONSTRAINT ck_games_status,
    ADD CONSTRAINT ck_games_status CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED'));

--rollback ALTER TABLE games DROP CONSTRAINT ck_games_status;
--rollback ALTER TABLE games ADD CONSTRAINT ck_games_status CHECK (status IN ('DRAFT', 'PENDING_MODERATION', 'OPEN', 'CLOSED'));

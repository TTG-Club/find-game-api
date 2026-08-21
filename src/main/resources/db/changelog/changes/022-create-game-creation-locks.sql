--liquibase formatted sql

--changeset codex:022-create-game-creation-locks
CREATE TABLE game_creation_locks
(
    master_id UUID NOT NULL PRIMARY KEY
);

CREATE INDEX ix_games_master_active
    ON games (master_id, status)
    WHERE deleted_at IS NULL;

--rollback DROP INDEX ix_games_master_active;
--rollback DROP TABLE game_creation_locks;

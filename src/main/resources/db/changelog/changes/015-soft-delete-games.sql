--liquibase formatted sql

--changeset codex:015-soft-delete-games
ALTER TABLE games
    ADD COLUMN deleted_at TIMESTAMPTZ;

DROP INDEX ix_games_public_created_at;
DROP INDEX ix_games_public_filters;

CREATE INDEX ix_games_public_created_at
    ON games (created_at DESC)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;

CREATE INDEX ix_games_public_filters
    ON games (game_system, game_type, created_at DESC)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;

--rollback DROP INDEX ix_games_public_created_at;
--rollback DROP INDEX ix_games_public_filters;
--rollback ALTER TABLE games DROP COLUMN deleted_at;
--rollback CREATE INDEX ix_games_public_created_at ON games (created_at DESC) WHERE visibility = 'PUBLIC';
--rollback CREATE INDEX ix_games_public_filters ON games (game_system, game_type, created_at DESC) WHERE visibility = 'PUBLIC';

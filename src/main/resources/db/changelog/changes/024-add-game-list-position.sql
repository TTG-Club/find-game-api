--liquibase formatted sql

--changeset codex:024-add-game-list-position
ALTER TABLE games
    ADD COLUMN list_position_at TIMESTAMPTZ;

UPDATE games
SET list_position_at = created_at;

ALTER TABLE games
    ALTER COLUMN list_position_at SET NOT NULL;

DROP INDEX ix_games_public_created_at;
DROP INDEX ix_games_public_filters;

CREATE INDEX ix_games_public_list_position
    ON games (list_position_at DESC, id DESC)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;

CREATE INDEX ix_games_public_filters
    ON games (game_system, game_type, list_position_at DESC)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;

--rollback DROP INDEX ix_games_public_list_position;
--rollback DROP INDEX ix_games_public_filters;
--rollback CREATE INDEX ix_games_public_created_at ON games (created_at DESC) WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;
--rollback CREATE INDEX ix_games_public_filters ON games (game_system, game_type, created_at DESC) WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;
--rollback ALTER TABLE games DROP COLUMN list_position_at;

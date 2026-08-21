--liquibase formatted sql

--changeset codex:021-add-text-game-type
ALTER TABLE games DROP CONSTRAINT ck_games_type;
ALTER TABLE games
    ADD CONSTRAINT ck_games_type CHECK (game_type IN ('ONLINE', 'TEXT', 'OFFLINE'));

--rollback ALTER TABLE games DROP CONSTRAINT ck_games_type;
--rollback ALTER TABLE games ADD CONSTRAINT ck_games_type CHECK (game_type IN ('ONLINE', 'OFFLINE'));

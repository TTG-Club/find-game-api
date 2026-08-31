--liquibase formatted sql

--changeset codex:029-add-cancelled-status
-- Отмена — отдельный исход, а не разновидность завершения: игра или сессия
-- может не состояться, и «завершена» про такую читалось бы неправдой.
ALTER TABLE games
    DROP CONSTRAINT ck_games_status,
    ADD CONSTRAINT ck_games_status CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'CANCELLED'));

ALTER TABLE game_sessions
    DROP CONSTRAINT ck_game_sessions_status,
    ADD CONSTRAINT ck_game_sessions_status
        CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'));

--rollback UPDATE game_sessions SET status = 'COMPLETED' WHERE status = 'CANCELLED';
--rollback UPDATE games SET status = 'CLOSED' WHERE status = 'CANCELLED';
--rollback ALTER TABLE game_sessions DROP CONSTRAINT ck_game_sessions_status, ADD CONSTRAINT ck_game_sessions_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED'));
--rollback ALTER TABLE games DROP CONSTRAINT ck_games_status, ADD CONSTRAINT ck_games_status CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED'));

--liquibase formatted sql

--changeset codex:039-add-game-recruitment-flag
-- Набор в игру закрывает мастер: группа собрана раньше, чем кончились места, и
-- новые заявки ему уже не нужны. Полный стол закрывается сам — там просто нет
-- свободного места, и отдельной отметки для этого не нужно.
ALTER TABLE games
    ADD COLUMN recruitment_closed BOOLEAN NOT NULL DEFAULT FALSE;

--rollback ALTER TABLE games DROP COLUMN recruitment_closed;

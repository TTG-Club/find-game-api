--liquibase formatted sql

--changeset codex:040-add-game-venue
-- Где именно собираются: клуб, антикафе, чей-то стол. Города для этого мало —
-- по нему игрок понимает, доедет ли вообще, а по месту уже как добираться.
-- Только для игр вживую: онлайн собирается по ссылке.
ALTER TABLE games
    ADD COLUMN venue VARCHAR(300);

--rollback ALTER TABLE games DROP COLUMN venue;

--liquibase formatted sql

--changeset codex:026-allow-open-session-date
-- Набор с открытой датой: мастер объявляет сессию, собирает игроков и только
-- потом назначает время. До назначения starts_at пуст.
ALTER TABLE game_sessions
    ALTER COLUMN starts_at DROP NOT NULL;

--rollback UPDATE game_sessions SET starts_at = now() WHERE starts_at IS NULL;
--rollback ALTER TABLE game_sessions ALTER COLUMN starts_at SET NOT NULL;

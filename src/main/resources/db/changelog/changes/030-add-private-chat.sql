--liquibase formatted sql

--changeset codex:030-add-private-chat
-- Личная переписка игрока с мастером игры. Третий вид комнаты рядом с общим
-- чатом игры и чатами сессий: адрес комнаты — игра и игрок, время сессии тут
-- ни при чём.
ALTER TABLE chat_events
    ADD COLUMN player_id UUID;

-- Личная комната всегда принадлежит игре целиком, а не отдельной сессии.
ALTER TABLE chat_events
    ADD CONSTRAINT ck_chat_events_private CHECK (player_id IS NULL OR session_id IS NULL);

-- Историю личной переписки читают по паре «игра + игрок» и свежести.
CREATE INDEX idx_chat_events_private
    ON chat_events (game_id, player_id, created_at DESC)
    WHERE player_id IS NOT NULL;

--rollback DROP INDEX idx_chat_events_private;
--rollback ALTER TABLE chat_events DROP CONSTRAINT ck_chat_events_private;
--rollback ALTER TABLE chat_events DROP COLUMN player_id;

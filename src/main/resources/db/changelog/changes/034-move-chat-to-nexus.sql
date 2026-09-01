--liquibase formatted sql

--changeset codex:034-move-chat-to-nexus
-- Чат переезжает из игры в нексус: комната стала местом, где группа общается,
-- и вести переписку в двух местах незачем.
ALTER TABLE chat_events
    ADD COLUMN nexus_id UUID;

ALTER TABLE chat_events
    ADD CONSTRAINT fk_chat_events_nexus FOREIGN KEY (nexus_id)
        REFERENCES nexuses (id) ON DELETE CASCADE;

-- Игра больше не обязательна: у самостоятельной комнаты её нет вовсе.
ALTER TABLE chat_events
    ALTER COLUMN game_id DROP NOT NULL;

-- Комнаты для игр, где уже успели поговорить: без них истории некуда переехать.
INSERT INTO nexuses (id, title, owner_id, invite_code, game_id, created_at, updated_at)
SELECT gen_random_uuid(), g.title, g.master_id, NULL, g.id, NOW(), NOW()
FROM games g
WHERE g.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM chat_events e
              WHERE e.game_id = g.id AND e.player_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM nexuses n WHERE n.game_id = g.id);

-- Групповые разговоры игры — общий чат и чаты сессий — сливаются в один чат
-- комнаты: и то и другое обсуждала вся группа.
UPDATE chat_events e
SET nexus_id = n.id
FROM nexuses n
WHERE n.game_id = e.game_id
  AND e.player_id IS NULL;

-- Личная переписка мастера с игроком остаётся без комнаты: такой переписки в
-- новой модели нет, а сливать её в общий чат — значит раскрыть её всем.
-- Записи не удаляются: они просто перестают быть видны через API.

CREATE INDEX idx_chat_events_nexus
    ON chat_events (nexus_id, created_at DESC, id DESC);

--rollback DROP INDEX idx_chat_events_nexus;
--rollback ALTER TABLE chat_events DROP CONSTRAINT fk_chat_events_nexus;
--rollback ALTER TABLE chat_events DROP COLUMN nexus_id;

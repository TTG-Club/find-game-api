--liquibase formatted sql

--changeset codex:023-create-chat-events
CREATE TABLE chat_events
(
    id                UUID         NOT NULL PRIMARY KEY,
    game_id           UUID         NOT NULL REFERENCES games (id),
    session_id        UUID         REFERENCES game_sessions (id),
    author_id         UUID         NOT NULL,
    client_message_id UUID         NOT NULL,
    event_type        VARCHAR(30)  NOT NULL,
    content           TEXT,
    payload           JSONB,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_chat_events_author_client_message UNIQUE (author_id, client_message_id),
    CONSTRAINT ck_chat_events_scope CHECK (session_id IS NULL OR game_id IS NOT NULL)
);

CREATE INDEX ix_chat_events_game_history
    ON chat_events (game_id, created_at DESC, id DESC)
    WHERE session_id IS NULL;

CREATE INDEX ix_chat_events_session_history
    ON chat_events (session_id, created_at DESC, id DESC)
    WHERE session_id IS NOT NULL;

--rollback DROP TABLE chat_events;

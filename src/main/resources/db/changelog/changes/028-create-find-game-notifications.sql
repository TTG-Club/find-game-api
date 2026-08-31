--liquibase formatted sql

--changeset codex:028-create-find-game-notifications
-- Лента уведомлений поиска игр: мастеру о новых заявках, игроку о решении по
-- его заявке и о начале и завершении сессии. Названия игры и сессии хранятся
-- копией, чтобы лента читалась и после переименования или удаления игры.
CREATE TABLE find_game_notifications
(
    id            UUID         PRIMARY KEY,
    recipient_id  UUID         NOT NULL,
    type          VARCHAR(40)  NOT NULL,
    game_id       UUID         NOT NULL,
    game_title    VARCHAR(150) NOT NULL,
    session_id    UUID,
    session_title VARCHAR(150),
    read_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Лента всегда читается по получателю и свежести.
CREATE INDEX idx_find_game_notifications_recipient
    ON find_game_notifications (recipient_id, created_at DESC);

-- Счётчик на колокольчике считает только непрочитанные.
CREATE INDEX idx_find_game_notifications_unread
    ON find_game_notifications (recipient_id)
    WHERE read_at IS NULL;

--rollback DROP TABLE find_game_notifications;

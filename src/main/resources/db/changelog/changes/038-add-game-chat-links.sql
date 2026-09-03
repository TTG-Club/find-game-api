--liquibase formatted sql

--changeset codex:038-add-game-chat-links
-- Разговоры группы живут там, где группа привыкла: в телеграме, дискорде,
-- вотсапе. Мастер оставляет две ссылки — на разговор с собой и на чат самой
-- игры. Первая открыта всем, кто смотрит объявление: без неё не о чем
-- договариваться до заявки. Вторую видят только принятые игроки, поэтому
-- прячет её выдача, а не хранилище.
ALTER TABLE games
    ADD COLUMN master_chat_url VARCHAR(2048),
    ADD COLUMN game_chat_url   VARCHAR(2048);

--rollback ALTER TABLE games DROP COLUMN master_chat_url, DROP COLUMN game_chat_url;

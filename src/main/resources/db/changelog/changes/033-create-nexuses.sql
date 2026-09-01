--liquibase formatted sql

--changeset codex:033-create-nexuses
-- Нексус — игровая комната группы: чат, листы персонажей, инициатива, лут и
-- выдача магических предметов. Существует сам по себе и не зависит от поиска
-- игр: у игры есть своя комната, но комната бывает и без игры.
CREATE TABLE nexuses
(
    id          UUID         NOT NULL,
    title       VARCHAR(150) NOT NULL,
    owner_id    UUID         NOT NULL,
    -- Код приглашения есть у самостоятельной комнаты: в неё зовут ссылкой.
    -- У комнаты игры его нет — туда попадают только со страницы игры.
    invite_code UUID,
    -- Игра, чью комнату описывает запись; NULL — самостоятельная комната.
    game_id     UUID,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_nexuses PRIMARY KEY (id),
    CONSTRAINT fk_nexuses_game FOREIGN KEY (game_id)
        REFERENCES games (id) ON DELETE CASCADE,
    -- У игры ровно одна комната.
    CONSTRAINT uq_nexuses_game UNIQUE (game_id),
    CONSTRAINT uq_nexuses_invite_code UNIQUE (invite_code),
    CONSTRAINT ck_nexuses_title CHECK (LENGTH(BTRIM(title)) > 0),
    -- Либо комната игры без кода, либо самостоятельная с кодом: комната игры
    -- по ссылке не зовёт, а самостоятельную иначе не открыть никому.
    CONSTRAINT ck_nexuses_origin CHECK (
        (game_id IS NOT NULL AND invite_code IS NULL)
        OR (game_id IS NULL AND invite_code IS NOT NULL))
);

CREATE INDEX idx_nexuses_owner ON nexuses (owner_id, created_at DESC);

-- Состав самостоятельной комнаты. У комнаты игры состава здесь нет: его
-- определяют заявки в игру, и дублировать этот список значило бы заводить
-- второй источник правды.
CREATE TABLE nexus_members
(
    id        UUID        NOT NULL,
    nexus_id  UUID        NOT NULL,
    user_id   UUID        NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_nexus_members PRIMARY KEY (id),
    CONSTRAINT fk_nexus_members_nexus FOREIGN KEY (nexus_id)
        REFERENCES nexuses (id) ON DELETE CASCADE,
    CONSTRAINT uq_nexus_members_user UNIQUE (nexus_id, user_id)
);

CREATE INDEX idx_nexus_members_user ON nexus_members (user_id, joined_at DESC);

--rollback DROP TABLE nexus_members;
--rollback DROP TABLE nexuses;

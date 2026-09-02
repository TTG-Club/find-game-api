--liquibase formatted sql

--changeset codex:035-create-nexus-character-sheets
-- Листы персонажей, выложенные в комнату. Сам лист живёт в core-api: здесь
-- хранится только ссылка на него — токен общего доступа — и подпись, по
-- которой его узнают за столом.
CREATE TABLE nexus_character_sheets
(
    id             UUID         NOT NULL,
    nexus_id       UUID         NOT NULL,
    -- Кто выложил лист: он же его и убирает.
    owner_id       UUID         NOT NULL,
    -- Токен общего доступа листа; по нему лист открывается всей комнате.
    share_token    VARCHAR(255) NOT NULL,
    character_name VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_nexus_character_sheets PRIMARY KEY (id),
    CONSTRAINT fk_nexus_character_sheets_nexus FOREIGN KEY (nexus_id)
        REFERENCES nexuses (id) ON DELETE CASCADE,
    -- Один и тот же лист не выкладывают в комнату дважды.
    CONSTRAINT uq_nexus_character_sheets_token UNIQUE (nexus_id, share_token),
    CONSTRAINT ck_nexus_character_sheets_token CHECK (LENGTH(BTRIM(share_token)) > 0),
    CONSTRAINT ck_nexus_character_sheets_name CHECK (LENGTH(BTRIM(character_name)) > 0)
);

-- Листы комнаты читают целиком и в порядке появления.
CREATE INDEX idx_nexus_character_sheets_nexus
    ON nexus_character_sheets (nexus_id, created_at);

--rollback DROP TABLE nexus_character_sheets;

--liquibase formatted sql

--changeset codex:036-create-nexus-trackers
-- Трекеры инициативы комнаты. Сам трекер живёт в core-api — там же, где
-- бестиарий и листы, которыми он наполняется; комната хранит лишь ссылку на
-- него, чтобы группа находила свой бой, не роясь в общем списке трекеров.
CREATE TABLE nexus_trackers
(
    id         UUID         NOT NULL,
    nexus_id   UUID         NOT NULL,
    -- Идентификатор трекера в core-api.
    tracker_id UUID         NOT NULL,
    -- Снимок названия: трекер могут переименовать, но список комнаты должен
    -- читаться и без похода в core-api.
    title      VARCHAR(150) NOT NULL,
    created_by UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_nexus_trackers PRIMARY KEY (id),
    CONSTRAINT fk_nexus_trackers_nexus FOREIGN KEY (nexus_id)
        REFERENCES nexuses (id) ON DELETE CASCADE,
    -- Один трекер не заводят в комнате дважды.
    CONSTRAINT uq_nexus_trackers_tracker UNIQUE (nexus_id, tracker_id),
    CONSTRAINT ck_nexus_trackers_title CHECK (LENGTH(BTRIM(title)) > 0)
);

-- Трекеры комнаты читают целиком, свежие первыми.
CREATE INDEX idx_nexus_trackers_nexus
    ON nexus_trackers (nexus_id, created_at DESC);

--rollback DROP TABLE nexus_trackers;

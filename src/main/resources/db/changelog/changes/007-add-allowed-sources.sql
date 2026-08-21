--liquibase formatted sql

--changeset codex:007-add-allowed-sources
CREATE TABLE game_allowed_sources
(
    game_id UUID         NOT NULL,
    source  VARCHAR(120) NOT NULL,

    CONSTRAINT pk_game_allowed_sources PRIMARY KEY (game_id, source),
    CONSTRAINT fk_game_allowed_sources_game FOREIGN KEY (game_id) REFERENCES games (id) ON DELETE CASCADE,
    CONSTRAINT ck_game_allowed_sources_not_blank CHECK (LENGTH(BTRIM(source)) > 0)
);

CREATE INDEX ix_game_allowed_sources_source ON game_allowed_sources (source);

--rollback DROP TABLE game_allowed_sources;

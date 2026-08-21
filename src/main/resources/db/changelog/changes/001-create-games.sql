--liquibase formatted sql

--changeset codex:001-create-games
CREATE TABLE games
(
    id               UUID         NOT NULL PRIMARY KEY,
    master_id        UUID         NOT NULL,
    title            VARCHAR(150) NOT NULL,
    game_system      VARCHAR(30)  NOT NULL,
    image_url        VARCHAR(2048),
    description      TEXT         NOT NULL,
    requirements     TEXT         NOT NULL,
    game_type        VARCHAR(20)  NOT NULL,
    players_to_start INTEGER      NOT NULL,
    max_players      INTEGER      NOT NULL,
    visibility       VARCHAR(20)  NOT NULL,
    invite_code      UUID UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_games_system CHECK (game_system IN ('DND_2024', 'DND_2014')),
    CONSTRAINT ck_games_type CHECK (game_type IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT ck_games_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT ck_games_players_to_start CHECK (players_to_start BETWEEN 1 AND 100),
    CONSTRAINT ck_games_max_players CHECK (max_players BETWEEN 1 AND 100),
    CONSTRAINT ck_games_player_counts CHECK (players_to_start <= max_players),
    CONSTRAINT ck_games_invite_code CHECK (
        (visibility = 'PRIVATE' AND invite_code IS NOT NULL)
        OR (visibility = 'PUBLIC' AND invite_code IS NULL)
    )
);

CREATE INDEX ix_games_public_created_at
    ON games (created_at DESC)
    WHERE visibility = 'PUBLIC';

CREATE INDEX ix_games_public_filters
    ON games (game_system, game_type, created_at DESC)
    WHERE visibility = 'PUBLIC';

CREATE INDEX ix_games_master_id ON games (master_id);

--rollback DROP TABLE games;

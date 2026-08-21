--liquibase formatted sql

--changeset codex:008-create-game-sessions
CREATE TABLE game_sessions
(
    id        UUID         NOT NULL,
    game_id   UUID         NOT NULL,
    title     VARCHAR(150) NOT NULL,
    starts_at TIMESTAMPTZ  NOT NULL,
    status    VARCHAR(30)  NOT NULL,

    CONSTRAINT pk_game_sessions PRIMARY KEY (id),
    CONSTRAINT fk_game_sessions_game FOREIGN KEY (game_id) REFERENCES games (id) ON DELETE CASCADE,
    CONSTRAINT ck_game_sessions_title_not_blank CHECK (LENGTH(BTRIM(title)) > 0),
    CONSTRAINT ck_game_sessions_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX ix_game_sessions_game_start ON game_sessions (game_id, starts_at);

CREATE TABLE game_session_players
(
    session_id UUID NOT NULL,
    player_id  UUID NOT NULL,

    CONSTRAINT pk_game_session_players PRIMARY KEY (session_id, player_id),
    CONSTRAINT fk_game_session_players_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE CASCADE
);

CREATE INDEX ix_game_session_players_player ON game_session_players (player_id);

--rollback DROP TABLE game_session_players;
--rollback DROP TABLE game_sessions;

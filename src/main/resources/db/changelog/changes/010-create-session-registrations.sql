--liquibase formatted sql

--changeset codex:010-create-session-registrations
CREATE TABLE game_session_registrations
(
    id                  UUID          NOT NULL,
    session_id          UUID          NOT NULL,
    player_id           UUID          NOT NULL,
    character_sheet_url VARCHAR(2048),
    status              VARCHAR(20)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_game_session_registrations PRIMARY KEY (id),
    CONSTRAINT fk_game_session_registrations_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE CASCADE,
    CONSTRAINT uq_game_session_registrations_player UNIQUE (session_id, player_id),
    CONSTRAINT ck_game_session_registrations_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_game_session_registrations_sheet_url CHECK (
        character_sheet_url IS NULL OR LENGTH(BTRIM(character_sheet_url)) > 0)
);

INSERT INTO game_session_registrations
    (id, session_id, player_id, character_sheet_url, status, created_at, updated_at)
SELECT MD5(session_id::TEXT || ':' || player_id::TEXT)::UUID,
       session_id,
       player_id,
       NULL,
       'APPROVED',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM game_session_players;

CREATE INDEX ix_game_session_registrations_session_status
    ON game_session_registrations (session_id, status);
CREATE INDEX ix_game_session_registrations_player
    ON game_session_registrations (player_id);

DROP TABLE game_session_players;

--rollback CREATE TABLE game_session_players (session_id UUID NOT NULL, player_id UUID NOT NULL, CONSTRAINT pk_game_session_players PRIMARY KEY (session_id, player_id), CONSTRAINT fk_game_session_players_session FOREIGN KEY (session_id) REFERENCES game_sessions (id) ON DELETE CASCADE);
--rollback INSERT INTO game_session_players (session_id, player_id) SELECT session_id, player_id FROM game_session_registrations WHERE status = 'APPROVED';
--rollback CREATE INDEX ix_game_session_players_player ON game_session_players (player_id);
--rollback DROP TABLE game_session_registrations;

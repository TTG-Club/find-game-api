--liquibase formatted sql

--changeset codex:031-move-registrations-to-game
-- Заявка переезжает с сессии на игру: игрок записывается в игру целиком и
-- попадает во все её запланированные сессии. У сессии остаётся только то,
-- что по своей природе относится к встрече, — присутствие и оплата.
CREATE TABLE game_registrations
(
    id                  UUID          NOT NULL,
    game_id             UUID          NOT NULL,
    player_id           UUID          NOT NULL,
    character_sheet_url VARCHAR(2048),
    character_name      VARCHAR(100),
    status              VARCHAR(20)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_game_registrations PRIMARY KEY (id),
    CONSTRAINT fk_game_registrations_game FOREIGN KEY (game_id)
        REFERENCES games (id) ON DELETE CASCADE,
    CONSTRAINT uq_game_registrations_player UNIQUE (game_id, player_id),
    CONSTRAINT ck_game_registrations_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_game_registrations_sheet_url CHECK (
        character_sheet_url IS NULL OR LENGTH(BTRIM(character_sheet_url)) > 0)
);

-- Перенос: по игроку берётся лучший статус его прежних заявок в сессии этой
-- игры (принят > на рассмотрении > отклонён) и самая ранняя дата подачи.
-- Лист персонажа и имя берутся из той заявки, где они заполнены.
INSERT INTO game_registrations
    (id, game_id, player_id, character_sheet_url, character_name, status, created_at, updated_at)
SELECT gen_random_uuid(),
       session.game_id,
       registration.player_id,
       (ARRAY_REMOVE(ARRAY_AGG(registration.character_sheet_url ORDER BY registration.created_at), NULL))[1],
       (ARRAY_REMOVE(ARRAY_AGG(registration.character_name ORDER BY registration.created_at), NULL))[1],
       CASE
           WHEN BOOL_OR(registration.status = 'APPROVED') THEN 'APPROVED'
           WHEN BOOL_OR(registration.status = 'PENDING') THEN 'PENDING'
           ELSE 'REJECTED'
       END,
       MIN(registration.created_at),
       MAX(registration.updated_at)
FROM game_session_registrations registration
         JOIN game_sessions session ON session.id = registration.session_id
GROUP BY session.game_id, registration.player_id;

CREATE INDEX ix_game_registrations_game_status
    ON game_registrations (game_id, status);
CREATE INDEX ix_game_registrations_player
    ON game_registrations (player_id);

-- В сессии остаётся участие: присутствие и оплата. Статус и лист персонажа
-- переехали в заявку на игру, здесь они больше не нужны.
ALTER TABLE game_session_registrations
    DROP CONSTRAINT ck_game_session_registrations_status,
    DROP CONSTRAINT ck_game_session_registrations_sheet_url;

DELETE FROM game_session_registrations WHERE status <> 'APPROVED';

ALTER TABLE game_session_registrations
    DROP COLUMN status,
    DROP COLUMN character_sheet_url,
    DROP COLUMN character_name;

--rollback ALTER TABLE game_session_registrations ADD COLUMN status VARCHAR(20), ADD COLUMN character_sheet_url VARCHAR(2048), ADD COLUMN character_name VARCHAR(100);
--rollback UPDATE game_session_registrations SET status = 'APPROVED';
--rollback ALTER TABLE game_session_registrations ALTER COLUMN status SET NOT NULL;
--rollback ALTER TABLE game_session_registrations ADD CONSTRAINT ck_game_session_registrations_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
--rollback DROP TABLE game_registrations;

--liquibase formatted sql

--changeset codex:043-create-game-raises
-- Журнал поднятий игры в списке. Раньше хватало отметки последнего поднятия:
-- правило было «не чаще раза в столько-то». Теперь правило — сколько раз за
-- сутки, и по одной отметке его не проверить.
CREATE TABLE game_raises
(
    id        UUID        NOT NULL,
    game_id   UUID        NOT NULL,
    raised_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_game_raises PRIMARY KEY (id),
    CONSTRAINT fk_game_raises_game FOREIGN KEY (game_id)
        REFERENCES games (id) ON DELETE CASCADE
);

-- Считаем поднятия игры за последние сутки — по игре и времени.
CREATE INDEX idx_game_raises_game_time
    ON game_raises (game_id, raised_at DESC);

--rollback DROP TABLE game_raises;

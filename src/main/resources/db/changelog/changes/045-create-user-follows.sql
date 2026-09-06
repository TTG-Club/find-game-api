--liquibase formatted sql

--changeset codex:045-create-user-follows
-- Отметки участников друг о друге: игрок отмечает мастера, чтобы не
-- пропустить его новую игру, мастер отмечает игрока, чтобы позвать его в
-- следующую.
--
-- Оба направления живут в одной таблице: строка одинаковая — кто кого отметил
-- и когда, — а различает их вид, от которого зависит только смысл отметки.
-- Отметка односторонняя и согласия второй стороны не требует: это закладка в
-- своём списке, а не дружба.
CREATE TABLE user_follows
(
    id         UUID        NOT NULL,
    -- Кто отметил: владелец списка.
    owner_id   UUID        NOT NULL,
    -- Кого отметили.
    target_id  UUID        NOT NULL,
    -- MASTER_FOLLOW — игрок о мастере, PLAYER_BOOKMARK — мастер об игроке.
    kind       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_user_follows PRIMARY KEY (id),
    -- Повторная отметка ничего не добавляет: она либо уже стоит, либо снята.
    CONSTRAINT uq_user_follows_pair UNIQUE (owner_id, target_id, kind),
    CONSTRAINT ck_user_follows_not_self CHECK (owner_id <> target_id)
);

-- Свой список открывают целиком, свежие отметки сверху.
CREATE INDEX idx_user_follows_owner
    ON user_follows (owner_id, kind, created_at DESC);

-- Обратный ход: по мастеру находят тех, кому рассылать новую игру.
CREATE INDEX idx_user_follows_target
    ON user_follows (target_id, kind);

--rollback DROP TABLE user_follows;

--liquibase formatted sql

--changeset codex:044-add-session-completed-at
-- Момент, когда встреча объявлена завершённой. По нему считается окно на
-- оценку: сама дата встречи для этого не годится — мастер закрывает сессию
-- тогда, когда она действительно кончилась.
ALTER TABLE game_sessions
    ADD COLUMN completed_at TIMESTAMPTZ;

-- Для уже закрытых сессий точное время завершения неизвестно. Используем время
-- начала встречи: это не откроет заново окно отзывов для старых сессий.
UPDATE game_sessions
SET completed_at = starts_at
WHERE status = 'COMPLETED';

--rollback ALTER TABLE game_sessions DROP COLUMN completed_at;

--changeset codex:044-create-session-reviews
-- Взаимные оценки за встречу: игрок отвечает про мастера, мастер — про
-- каждого игрока. Одна строка — один вердикт одного человека о другом.
--
-- Направления живут в одной таблице: правила у них общие — писать может
-- только участник закрытой встречи, и только раз, — а различает их вид,
-- от которого зависит, кому оценку показывать.
CREATE TABLE session_reviews
(
    id                  UUID        NOT NULL,
    session_id          UUID        NOT NULL,
    -- Игра рядом с сессией: репутация собирается по мастеру и по игроку, а не
    -- по одной встрече, и джойн ради этого не нужен.
    game_id             UUID        NOT NULL,
    author_id           UUID        NOT NULL,
    target_id           UUID        NOT NULL,
    -- MASTER_REVIEW — игрок о мастере, PLAYER_REVIEW — мастер об игроке.
    kind                VARCHAR(20) NOT NULL,
    -- «Сыграл бы снова»: на малых числах это честнее пятизвёздочной шкалы,
    -- которая быстро схлопывается в сплошные пятёрки.
    recommended         BOOLEAN     NOT NULL,
    comment             TEXT,
    -- Отметка завершения встречи, скопированная при написании: по ней видно,
    -- закрылось ли окно, без обращения к сессии.
    session_completed_at TIMESTAMPTZ NOT NULL,
    -- Момент раскрытия: ставится, когда ответила вторая сторона. Пока пусто и
    -- окно не вышло, оценку видит только её автор — иначе тот, кто увидел
    -- первым, отвечает тем же.
    visible_at          TIMESTAMPTZ,
    -- Скрыт модератором: история остаётся, из выдачи отзыв уходит.
    hidden_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_session_reviews PRIMARY KEY (id),
    CONSTRAINT fk_session_reviews_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE CASCADE,
    -- Один вердикт на пару за встречу: вторая оценка правит первую.
    CONSTRAINT uq_session_reviews_pair UNIQUE (session_id, author_id, target_id),
    CONSTRAINT ck_session_reviews_not_self CHECK (author_id <> target_id)
);

-- Репутация читается по адресату и виду, свежие первыми.
CREATE INDEX idx_session_reviews_target
    ON session_reviews (target_id, kind, created_at DESC);

-- Свои оценки за встречу автор находит по сессии.
CREATE INDEX idx_session_reviews_session
    ON session_reviews (session_id);

--rollback DROP TABLE session_reviews;

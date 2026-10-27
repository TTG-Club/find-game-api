--liquibase formatted sql

--changeset ttg:062-add-registration-approved-at
-- Когда мастер впервые принял игрока. По этой отметке игрок получает право
-- оценить мастера, даже если мастер не ведёт сессии на сайте. Исключение из
-- состава её не снимает: сыгравший у мастера вправе о нём рассказать.
ALTER TABLE game_registrations
    ADD COLUMN approved_at TIMESTAMPTZ;

-- Точное время принятия у старых заявок неизвестно, ближе всего к нему
-- последнее изменение заявки.
UPDATE game_registrations
SET approved_at = updated_at
WHERE status = 'APPROVED';

--rollback ALTER TABLE game_registrations DROP COLUMN approved_at;

--changeset ttg:062-create-game-reviews
-- Взаимные оценки за игру: игрок отвечает про мастера, мастер — про каждого
-- игрока. Одна строка на пару в игре: кампания из сотни встреч даёт один
-- голос, а не сотню.
CREATE TABLE game_reviews
(
    id          UUID        NOT NULL,
    game_id     UUID        NOT NULL,
    author_id   UUID        NOT NULL,
    target_id   UUID        NOT NULL,
    -- MASTER_REVIEW — игрок о мастере, PLAYER_REVIEW — мастер об игроке.
    kind        VARCHAR(20) NOT NULL,
    recommended BOOLEAN     NOT NULL,
    comment     TEXT,
    -- Момент раскрытия: ставится, когда ответила вторая сторона. Пока пусто,
    -- оценку видит только её автор — до истечения срока ожидания ответа.
    visible_at  TIMESTAMPTZ,
    -- Скрыт модератором: история остаётся, из выдачи отзыв уходит.
    hidden_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_game_reviews PRIMARY KEY (id),
    CONSTRAINT fk_game_reviews_game FOREIGN KEY (game_id)
        REFERENCES games (id) ON DELETE CASCADE,
    CONSTRAINT uq_game_reviews_pair UNIQUE (game_id, author_id, target_id),
    CONSTRAINT ck_game_reviews_not_self CHECK (author_id <> target_id)
);

-- Репутация читается по адресату и виду, свежие первыми.
CREATE INDEX idx_game_reviews_target
    ON game_reviews (target_id, kind, created_at DESC);

-- Переносим оценки за встречи: от каждой пары в игре остаётся последняя.
-- Пара, у которой окно на оценку уже вышло, считается раскрытой.
INSERT INTO game_reviews (id, game_id, author_id, target_id, kind, recommended, comment,
                          visible_at, hidden_at, created_at, updated_at)
SELECT DISTINCT ON (review.game_id, review.author_id, review.target_id)
       review.id,
       review.game_id,
       review.author_id,
       review.target_id,
       review.kind,
       review.recommended,
       review.comment,
       COALESCE(review.visible_at,
                CASE
                    WHEN review.session_completed_at < NOW() - INTERVAL '14 days'
                        THEN review.session_completed_at + INTERVAL '14 days'
                END),
       review.hidden_at,
       review.created_at,
       review.updated_at
FROM session_reviews review
         JOIN games game ON game.id = review.game_id
ORDER BY review.game_id, review.author_id, review.target_id, review.updated_at DESC;

--rollback DROP TABLE game_reviews;

--changeset ttg:062-create-review-reminders
-- Кому уже напомнили оценить игру. Напоминание одно на человека в игре, а
-- строка с первичным ключом не даёт репликам сервиса отправить его дважды.
CREATE TABLE review_reminders
(
    game_id UUID        NOT NULL,
    user_id UUID        NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_review_reminders PRIMARY KEY (game_id, user_id),
    CONSTRAINT fk_review_reminders_game FOREIGN KEY (game_id)
        REFERENCES games (id) ON DELETE CASCADE
);

-- Тем, кто уже оценил, напоминать нечего: старые пары считаются напомненными.
INSERT INTO review_reminders (game_id, user_id, sent_at)
SELECT DISTINCT review.game_id, review.author_id, NOW()
FROM game_reviews review;

--rollback DROP TABLE review_reminders;

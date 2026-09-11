--liquibase formatted sql

--changeset codex:050-create-favorite-games
-- Личные закладки игроков на объявления: игру откладывают, чтобы вернуться к
-- ней позже — решиться, дождаться удобной даты или просто не потерять.
--
-- Отметка ничего не сообщает мастеру и не занимает места в составе: это
-- список у себя, а не заявка. Поэтому согласия второй стороны она не требует,
-- а видимость игры проверяется при чтении списка, а не при отметке.
create table favorite_games
(
    id         uuid        not null,
    -- Кто отметил: владелец списка.
    owner_id   uuid        not null,
    game_id    uuid        not null references games (id),
    created_at timestamptz not null,

    constraint pk_favorite_games primary key (id),
    -- Повторная отметка ничего не добавляет: она либо уже стоит, либо снята.
    constraint uq_favorite_games_owner_game unique (owner_id, game_id)
);

-- Свой список открывают целиком, свежие отметки сверху.
create index idx_favorite_games_owner on favorite_games (owner_id, created_at desc);

--rollback drop table favorite_games;

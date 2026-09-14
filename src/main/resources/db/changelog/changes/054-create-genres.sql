--liquibase formatted sql

--changeset codex:054-create-genres
create table genres (
    id uuid primary key,
    name varchar(100) not null,
    normalized_name varchar(100) not null unique
);

create table game_genres (
    game_id uuid not null references games(id) on delete cascade,
    genre_id uuid not null references genres(id),
    primary key (game_id, genre_id)
);

create index idx_game_genres_genre_id on game_genres(genre_id);

insert into genres (id, name, normalized_name)
select gen_random_uuid(), min(trim(genre)), lower(trim(genre))
from games
where genre is not null and trim(genre) <> ''
group by lower(trim(genre));

insert into game_genres (game_id, genre_id)
select game.id, genre.id
from games game
join genres genre on genre.normalized_name = lower(trim(game.genre))
where game.genre is not null and trim(game.genre) <> '';

alter table games drop column genre;

--rollback alter table games add column genre varchar(100);
--rollback update games game set genre = (select min(genre.name) from game_genres link join genres genre on genre.id = link.genre_id where link.game_id = game.id);
--rollback drop table game_genres;
--rollback drop table genres;

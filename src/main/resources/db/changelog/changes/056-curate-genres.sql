--liquibase formatted sql

--changeset codex:056-curate-genres
-- Жанры выбираются из готового списка, а свой жанр мастер пишет отдельным
-- полем игры: иначе справочник расползается на написания одного и того же, и
-- отбор по жанру в каталоге теряет смысл.
alter table games
    add column custom_genre varchar(100);

alter table genres
    add column curated boolean not null default false;

-- Совпавшее по написанию прежнее значение становится жанром списка: его связи
-- с играми остаются на месте, меняется только написание.
insert into genres (id, name, normalized_name, curated)
select gen_random_uuid(), curated.name, lower(curated.name), true
from (values ('Героическое фэнтези'),
             ('Меч и магия'),
             ('Эпическое фэнтези'),
             ('Мифическое фэнтези'),
             ('Тёмное фэнтези'),
             ('Интриги'),
             ('Детектив'),
             ('Плащ и шпага'),
             ('Война'),
             ('Уся'),
             ('Городское фэнтези'),
             ('Низкое фэнтези'),
             ('Хоррор'),
             ('Мистика'),
             ('Нуар'),
             ('Триллер'),
             ('Шпионаж'),
             ('Научная фантастика'),
             ('Космоопера'),
             ('Киберпанк'),
             ('Стимпанк'),
             ('Постапокалипсис'),
             ('Выживание'),
             ('Супергерои'),
             ('Современность'),
             ('Историческое'),
             ('Вестерн'),
             ('Пираты'),
             ('Исследование подземелий'),
             ('Песочница'),
             ('Комедия'),
             ('Драма')) as curated(name)
on conflict (normalized_name) do update set name = excluded.name, curated = true;

-- Вписанные раньше жанры не пропадают, а переезжают в свой жанр игры.
update games game
set custom_genre = left(custom.names, 100)
from (select link.game_id, string_agg(genre.name, ', ' order by genre.name) as names
      from game_genres link
               join genres genre on genre.id = link.genre_id
      where not genre.curated
      group by link.game_id) custom
where game.id = custom.game_id;

delete
from game_genres link
    using genres genre
where genre.id = link.genre_id
  and not genre.curated;

delete
from genres
where not curated;

alter table genres
    drop column curated;

--rollback alter table games drop column custom_genre;

--liquibase formatted sql

--changeset codex:053-create-game-systems
create table game_systems
(
    code varchar(30) not null primary key,
    name varchar(120) not null unique
);

insert into game_systems (code, name)
values ('DND_2024', 'D&D 5 (2024)'),
       ('DND_2014', 'D&D 5 (2014)');

alter table games
    drop constraint ck_games_system;

alter table games
    add constraint fk_games_game_system
        foreign key (game_system) references game_systems (code);

--rollback alter table games drop constraint fk_games_game_system;
--rollback alter table games add constraint ck_games_system check (game_system in ('DND_2024', 'DND_2014'));
--rollback drop table game_systems;

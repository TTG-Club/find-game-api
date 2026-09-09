--liquibase formatted sql

--changeset codex:047-add-game-online-platform
-- у старых игр платформа неизвестна: не подменяем её значением по умолчанию.
alter table games add column online_platform varchar(32);
alter table games add constraint chk_games_online_platform_type
    check (online_platform is null or game_type = upper('online'));

--rollback alter table games drop constraint chk_games_online_platform_type;
--rollback alter table games drop column online_platform;

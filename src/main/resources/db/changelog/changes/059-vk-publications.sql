--liquibase formatted sql

--changeset ttg:059-vk-publications
-- Проверка платформы из 058 создана без имени: PostgreSQL и H2 называют её по-разному.
-- Столбец пересоздаётся с именованной проверкой; данные копируются, прежняя проверка уходит вместе со старым столбцом.
-- Форма case, как в 058: H2 в режиме PostgreSQL отклоняет параметр запроса в проверке через in (...).
alter table discord_publication_channels rename column platform to platform_previous;
alter table discord_publication_channels add column platform varchar(16) not null default 'discord';
update discord_publication_channels set platform = platform_previous;
alter table discord_publication_channels drop column platform_previous;
alter table discord_publication_channels add constraint ck_discord_publication_channels_platform check (case platform when 'discord' then true when 'telegram' then true when 'vk' then true else false end);

alter table discord_publication_runs rename column platform to platform_previous;
alter table discord_publication_runs add column platform varchar(16) not null default 'discord';
update discord_publication_runs set platform = platform_previous;
alter table discord_publication_runs drop column platform_previous;
alter table discord_publication_runs add constraint ck_discord_publication_runs_platform check (case platform when 'discord' then true when 'telegram' then true when 'vk' then true else false end);

alter table discord_publication_settings add column vk_paused_until timestamp with time zone;

--rollback alter table discord_publication_settings drop column vk_paused_until;
--rollback alter table discord_publication_runs drop constraint ck_discord_publication_runs_platform;
--rollback alter table discord_publication_runs add constraint ck_discord_publication_runs_platform check (case platform when 'discord' then true when 'telegram' then true else false end);
--rollback alter table discord_publication_channels drop constraint ck_discord_publication_channels_platform;
--rollback alter table discord_publication_channels add constraint ck_discord_publication_channels_platform check (case platform when 'discord' then true when 'telegram' then true else false end);

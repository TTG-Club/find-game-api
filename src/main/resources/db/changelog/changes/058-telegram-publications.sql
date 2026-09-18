--liquibase formatted sql

--changeset ttg:058-telegram-publications
-- прежние таблицы и зашифрованные вебхуки сохраняются для обновления без потери данных
alter table discord_publication_channels add column platform varchar(16) not null default 'discord' check (case platform when 'discord' then true when 'telegram' then true else false end);
alter table discord_publication_runs add column platform varchar(16) not null default 'discord' check (case platform when 'discord' then true when 'telegram' then true else false end);
alter table discord_publication_settings add column telegram_paused_until timestamp with time zone;

--rollback alter table discord_publication_settings drop column telegram_paused_until;
--rollback alter table discord_publication_runs drop column platform;
--rollback alter table discord_publication_channels drop column platform;

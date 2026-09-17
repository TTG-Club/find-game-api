--liquibase formatted sql

--changeset ttg:057-discord-publications
create table discord_publication_settings (
    id integer primary key check (id = 1),
    enabled boolean not null default false,
    schedule text not null default '[]',
    revision bigint not null default 0,
    paused_until timestamp with time zone
);
insert into discord_publication_settings (id) values (1);

create table discord_publication_channels (
    id uuid primary key,
    name varchar(100) not null,
    webhook_secret text not null,
    webhook_fingerprint varchar(64) not null unique,
    enabled boolean not null,
    schedule text,
    revision bigint not null default 0,
    next_run_at timestamp with time zone
);
create index ix_discord_channels_due on discord_publication_channels (next_run_at);

create table discord_publication_runs (
    id uuid primary key,
    channel_id uuid references discord_publication_channels(id) on delete set null,
    channel_name varchar(100) not null,
    channel_revision bigint not null,
    scheduled_at timestamp with time zone not null,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    retry_at timestamp with time zone,
    attempts integer not null default 1,
    status varchar(20) not null,
    game_count integer not null default 0,
    message_id varchar(30),
    detail varchar(300) not null default '',
    unique(channel_id, scheduled_at)
);
create index ix_discord_runs_status on discord_publication_runs (status, retry_at);
create index ix_discord_runs_history on discord_publication_runs (scheduled_at desc);

--rollback drop table discord_publication_runs;
--rollback drop table discord_publication_channels;
--rollback drop table discord_publication_settings;

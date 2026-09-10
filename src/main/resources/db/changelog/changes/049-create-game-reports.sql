--liquibase formatted sql

--changeset codex:049-create-game-reports
create table game_reports (
    id uuid primary key,
    game_id uuid not null references games(id),
    reporter_id uuid not null,
    reason varchar(32) not null,
    details varchar(1000),
    created_at timestamptz not null,
    constraint uq_game_reports_game_reporter unique (game_id, reporter_id)
);
create index idx_game_reports_created_at on game_reports(created_at desc);

--rollback drop table game_reports;

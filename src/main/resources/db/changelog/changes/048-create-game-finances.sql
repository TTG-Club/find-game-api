--liquibase formatted sql

--changeset codex:048-create-game-finances
create table game_finance_entries (
    id uuid primary key,
    game_id uuid not null references games(id),
    player_id uuid not null,
    session_id uuid references game_sessions(id),
    amount numeric(14, 2) not null,
    currency varchar(3) not null,
    kind varchar(30) not null,
    comment varchar(500),
    actor_id uuid not null,
    created_at timestamptz not null
);
create index idx_finance_entries_account on game_finance_entries(game_id, player_id, created_at);
create table game_session_bills (
    id uuid primary key,
    game_id uuid not null references games(id),
    session_id uuid not null references game_sessions(id),
    player_id uuid not null,
    amount numeric(12, 2) not null,
    currency varchar(3) not null,
    booked numeric(12, 2) not null default 0,
    settled numeric(12, 2) not null default 0,
    claimed numeric(12, 2) not null default 0,
    finalized boolean not null default false,
    exempt boolean not null default false,
    created_at timestamptz not null,
    unique(session_id, player_id),
    check (booked >= 0 and booked <= amount and settled >= 0 and settled <= amount and claimed >= 0)
);
create index idx_session_bills_account on game_session_bills(game_id, player_id, created_at);

--rollback drop table game_session_bills;
--rollback drop table game_finance_entries;

--liquibase formatted sql

--changeset codex:046-default-session-attendance
-- Старые отказы сохраняются: их нельзя отличить от прежнего значения по умолчанию.
alter table game_session_registrations
    alter column attendance_status set default upper('unmarked');

--rollback alter table game_session_registrations alter column attendance_status drop default;

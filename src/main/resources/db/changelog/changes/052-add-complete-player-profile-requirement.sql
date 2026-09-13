--liquibase formatted sql

--changeset codex:052-add-complete-player-profile-requirement
alter table games
    add column requires_complete_player_profile boolean not null default false;

--rollback alter table games drop column requires_complete_player_profile;

--liquibase formatted sql

--changeset codex:017-create-user-profiles
CREATE TABLE user_profiles (
    user_id                    UUID        NOT NULL,
    birth_year                 SMALLINT,
    gender                     VARCHAR(30),
    tabletop_experience_years SMALLINT,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_user_profiles PRIMARY KEY (user_id),
    CONSTRAINT ck_user_profiles_birth_year CHECK (birth_year IS NULL OR birth_year BETWEEN 1900 AND 2100),
    CONSTRAINT ck_user_profiles_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER', 'NOT_SPECIFIED')),
    CONSTRAINT ck_user_profiles_experience CHECK (
        tabletop_experience_years IS NULL OR tabletop_experience_years BETWEEN 0 AND 100
    )
);

CREATE TABLE master_profiles (
    user_id UUID NOT NULL,
    about   TEXT,

    CONSTRAINT pk_master_profiles PRIMARY KEY (user_id),
    CONSTRAINT fk_master_profiles_user FOREIGN KEY (user_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_master_profiles_about CHECK (about IS NULL OR LENGTH(about) <= 5000)
);

CREATE TABLE player_profiles (
    user_id UUID NOT NULL,
    about   TEXT,

    CONSTRAINT pk_player_profiles PRIMARY KEY (user_id),
    CONSTRAINT fk_player_profiles_user FOREIGN KEY (user_id) REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_player_profiles_about CHECK (about IS NULL OR LENGTH(about) <= 5000)
);

--rollback DROP TABLE player_profiles;
--rollback DROP TABLE master_profiles;
--rollback DROP TABLE user_profiles;

--liquibase formatted sql

--changeset codex:041-create-cities
-- Справочник городов. Город игры остаётся строкой в самой игре: справочник
-- нужен, чтобы мастера писали «Санкт-Петербург» одинаково, а не пятью
-- способами — иначе фильтр каталога рассыпается на почти одинаковые значения.
CREATE TABLE cities
(
    id         UUID         NOT NULL,
    -- Название на русском: им город и подписан в объявлении.
    name       VARCHAR(120) NOT NULL,
    -- Область или штат: две Ростова и три Владимира иначе не различить.
    region     VARCHAR(120),
    country    VARCHAR(120) NOT NULL,
    -- Порядок подсказок: крупные города выше, чтобы «Мос» первым делом давал
    -- Москву, а не Мосальск.
    population INTEGER,

    CONSTRAINT pk_cities PRIMARY KEY (id),
    CONSTRAINT uq_cities_name UNIQUE (name, region, country),
    CONSTRAINT ck_cities_name CHECK (LENGTH(BTRIM(name)) > 0)
);

-- Поиск идёт по началу названия и без учёта регистра.
CREATE INDEX idx_cities_name_lower ON cities (LOWER(name));

--rollback DROP TABLE cities;

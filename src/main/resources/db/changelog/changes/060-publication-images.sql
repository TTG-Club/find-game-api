--liquibase formatted sql

--changeset ttg:060-publication-images
-- Картинка канала лежит в хранилище сайта; в базе только путь вида /s3/..., пустое значение — пост без картинки.
alter table discord_publication_channels add column image_url varchar(512);

--rollback alter table discord_publication_channels drop column image_url;

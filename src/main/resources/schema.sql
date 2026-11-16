-- SHARDING DEMO:
-- Instead of one big "photos" table, we split photos across 2 shards.
-- A photo goes to shard = hash(photoId) % 2.
-- In a real company, each shard would be a separate database server.
-- Here both live in one free database so it costs nothing, but the routing logic is the same.

CREATE TABLE IF NOT EXISTS photos_0 (
    id           VARCHAR(36)  PRIMARY KEY,
    owner        VARCHAR(40)  NOT NULL,
    filename     VARCHAR(200) NOT NULL,
    content_type VARCHAR(50)  NOT NULL,
    size_bytes   INT          NOT NULL,
    created_at   BIGINT       NOT NULL,
    data         BYTEA        NOT NULL
);

CREATE TABLE IF NOT EXISTS photos_1 (
    id           VARCHAR(36)  PRIMARY KEY,
    owner        VARCHAR(40)  NOT NULL,
    filename     VARCHAR(200) NOT NULL,
    content_type VARCHAR(50)  NOT NULL,
    size_bytes   INT          NOT NULL,
    created_at   BIGINT       NOT NULL,
    data         BYTEA        NOT NULL
);

-- Collections group albums in an order of their own, e.g. a spex's recordings over the years. An album can be in several.
CREATE TABLE album_collection
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)      NOT NULL,
    title            VARCHAR(255)  NOT NULL,
    description      VARCHAR(4000) NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_by       VARCHAR(255)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    last_modified_by VARCHAR(255)  NULL,
    last_modified_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_collection_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE album_collection_album
(
    collection_id BIGINT NOT NULL,
    album_id      BIGINT NOT NULL,
    position      INT    NOT NULL,
    PRIMARY KEY (collection_id, position),
    CONSTRAINT uk_album_collection_album UNIQUE (collection_id, album_id),
    CONSTRAINT fk_album_collection_album_collection FOREIGN KEY (collection_id) REFERENCES album_collection (id) ON DELETE CASCADE,
    CONSTRAINT fk_album_collection_album_album FOREIGN KEY (album_id) REFERENCES album (id) ON DELETE CASCADE,
    INDEX idx_album_collection_album_album (album_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

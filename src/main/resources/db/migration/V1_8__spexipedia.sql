-- Spex now come from Spexipedia, which also has the numbers (songs) of each spex; a track may be a recording of one.
-- The number's title is copied, like the spex data on albums, and kept current from Spexipedia's change feed.
ALTER TABLE track
    ADD COLUMN number_id    CHAR(36)     NULL,
    ADD COLUMN number_title VARCHAR(255) NULL,
    ADD INDEX idx_track_number (number_id);

-- How far Spexify has read Spexipedia's change feed: one row.
CREATE TABLE spexipedia_sync
(
    id             TINYINT       NOT NULL,
    cursor_value   VARCHAR(64)   NULL,
    last_pulled_at DATETIME(6)   NULL,
    last_success   DATETIME(6)   NULL,
    last_error     VARCHAR(1000) NULL,
    applied        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO spexipedia_sync (id) VALUES (1);

-- Category names by id, from the feed, for the spex copied onto albums
CREATE TABLE spexipedia_category
(
    id   CHAR(36)     NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

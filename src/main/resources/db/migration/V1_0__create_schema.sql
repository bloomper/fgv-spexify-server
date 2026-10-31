CREATE TABLE app_user
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    subject      VARCHAR(64)  NOT NULL,
    username     VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NULL,
    created_at   DATETIME(6)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_subject UNIQUE (subject),
    CONSTRAINT uk_app_user_username UNIQUE (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE album
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    uuid               CHAR(36)      NOT NULL,
    spex_id            BIGINT        NULL,
    spex_title         VARCHAR(255)  NULL,
    spex_year          SMALLINT      NULL,
    spex_revival       BIT           NULL,
    spex_category_id   BIGINT        NULL,
    spex_category_name VARCHAR(255)  NULL,
    spex_refreshed_at  DATETIME(6)   NULL,
    title              VARCHAR(255)  NOT NULL,
    year               SMALLINT      NULL,
    kind               VARCHAR(20)   NOT NULL,
    description        TEXT          NULL,
    cover_extension    VARCHAR(10)   NULL,
    published          BIT           NOT NULL DEFAULT 0,
    published_at       DATETIME(6)   NULL,
    trashed_at         DATETIME(6)   NULL,
    navidrome_id       VARCHAR(64)   NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         VARCHAR(255)  NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    last_modified_by   VARCHAR(255)  NULL,
    last_modified_at   DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_uuid UNIQUE (uuid),
    INDEX idx_album_spex (spex_id),
    INDEX idx_album_year (year),
    INDEX idx_album_category (spex_category_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE track
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)      NOT NULL,
    album_id         BIGINT        NOT NULL,
    disc_number      SMALLINT      NOT NULL DEFAULT 1,
    track_number     SMALLINT      NOT NULL,
    title            VARCHAR(255)  NOT NULL,
    performers       VARCHAR(1000) NULL,
    melody           VARCHAR(500)  NULL,
    lyrics_author    VARCHAR(255)  NULL,
    arranger         VARCHAR(255)  NULL,
    lyrics           MEDIUMTEXT    NULL,
    lyrics_synced    BIT           NOT NULL DEFAULT 0,
    file_extension   VARCHAR(10)   NOT NULL,
    file_format      VARCHAR(20)   NOT NULL,
    file_size        BIGINT        NOT NULL,
    file_checksum    CHAR(64)      NOT NULL,
    duration_ms      BIGINT        NOT NULL,
    bitrate          INT           NULL,
    location         VARCHAR(20)   NOT NULL,
    tags_hash        CHAR(64)      NULL,
    published        BIT           NOT NULL DEFAULT 1,
    trashed_at       DATETIME(6)   NULL,
    navidrome_id     VARCHAR(64)   NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_by       VARCHAR(255)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    last_modified_by VARCHAR(255)  NULL,
    last_modified_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_track_uuid UNIQUE (uuid),
    CONSTRAINT fk_track_album FOREIGN KEY (album_id) REFERENCES album (id),
    INDEX idx_track_album (album_id, disc_number, track_number),
    INDEX idx_track_navidrome (navidrome_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE app_password
(
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    user_id      BIGINT         NOT NULL,
    name         VARCHAR(100)   NOT NULL,
    ciphertext   VARBINARY(512) NOT NULL,
    created_at   DATETIME(6)    NOT NULL,
    last_used_at DATETIME(6)    NULL,
    revoked_at   DATETIME(6)    NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_app_password_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    INDEX idx_app_password_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE api_key
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    key_hash     CHAR(64)     NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    last_used_at DATETIME(6)  NULL,
    revoked_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash),
    CONSTRAINT fk_api_key_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE settings
(
    id                         BIGINT       NOT NULL,
    welcome_sv                 TEXT         NULL,
    welcome_en                 TEXT         NULL,
    app_password_expiry_months INT          NOT NULL,
    lockout_attempts           INT          NOT NULL,
    lockout_minutes            INT          NOT NULL,
    version                    BIGINT       NOT NULL DEFAULT 0,
    last_modified_by           VARCHAR(255) NULL,
    last_modified_at           DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO settings (id, app_password_expiry_months, lockout_attempts, lockout_minutes)
VALUES (1, 12, 10, 15);

CREATE TABLE audit_entry
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    at            DATETIME(6)   NOT NULL,
    actor_subject VARCHAR(64)   NOT NULL,
    actor_name    VARCHAR(255)  NULL,
    action        VARCHAR(40)   NOT NULL,
    entity_type   VARCHAR(20)   NOT NULL,
    entity_id     BIGINT        NULL,
    summary       VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_entry_at (at),
    INDEX idx_audit_entry_actor (actor_subject, at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE upload_session
(
    id           CHAR(36)      NOT NULL,
    owner        VARCHAR(64)   NOT NULL,
    length       BIGINT        NOT NULL,
    upload_offset BIGINT       NOT NULL,
    file_name    VARCHAR(255)  NULL,
    created_at   DATETIME(6)   NOT NULL,
    completed_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    INDEX idx_upload_session_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

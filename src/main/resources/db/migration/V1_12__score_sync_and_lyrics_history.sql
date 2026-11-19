-- Where in a score each moment of a recording is, so that the web's cursor follows the sheet music while the track
-- plays. The score is a MusicXML file in Spexarkivet (file_id, with the checksum it had); a map is made by hand
-- (MANUAL, which wins) or found by aligning the recording with the score (AUTOMATIC, with its quality). points is a
-- JSON list of [seconds, measure, beat] in the order of the recording; jumps (manual maps) a JSON list of
-- {after, to, nth}: how the recording walks through the written score.
CREATE TABLE score_sync
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    version          BIGINT       NOT NULL DEFAULT 0,
    track_id         BIGINT       NOT NULL,
    file_id          CHAR(36)     NOT NULL,
    method           VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    points           MEDIUMTEXT   NULL,
    jumps            TEXT         NULL,
    quality          DOUBLE       NULL,
    error            VARCHAR(1000) NULL,
    score_checksum   CHAR(64)     NULL,
    audio_checksum   CHAR(64)     NULL,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_score_sync UNIQUE (track_id, file_id, method),
    CONSTRAINT fk_score_sync_track FOREIGN KEY (track_id) REFERENCES track (id) ON DELETE CASCADE,
    INDEX idx_score_sync_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Every saved version of a track's lyrics, newest last, so that editors can see who changed them and restore one.
-- The first change through the web also keeps what was there before it (created_by_name null: imported or uploaded).
CREATE TABLE lyrics_revision
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    track_id        BIGINT       NOT NULL,
    lyrics          MEDIUMTEXT   NULL,
    lyrics_synced   BIT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(255) NOT NULL,
    created_by_name VARCHAR(255) NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_lyrics_revision_track FOREIGN KEY (track_id) REFERENCES track (id) ON DELETE CASCADE,
    INDEX idx_lyrics_revision_track (track_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

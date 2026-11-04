-- What each user has done with each track and album, mirrored from Navidrome, which keeps it per user only, so that
-- everyone's ratings, likes and plays can be summed up for averages and top lists. The Subsonic gateway records every
-- rating, like (star) and play (scrobble) that Navidrome accepts, and the LISTENING_SYNC job copies Navidrome's state
-- every night, which also fills these tables the first time.
CREATE TABLE track_listening
(
    id             BIGINT NOT NULL AUTO_INCREMENT,
    user_id        BIGINT NOT NULL,
    track_id       BIGINT NOT NULL,
    rating         TINYINT NULL,
    liked_at       DATETIME(6) NULL,
    play_count     INT    NOT NULL DEFAULT 0,
    last_played_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_track_listening UNIQUE (user_id, track_id),
    CONSTRAINT fk_track_listening_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_track_listening_track FOREIGN KEY (track_id) REFERENCES track (id) ON DELETE CASCADE,
    INDEX          idx_track_listening_track (track_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE album_listening
(
    id       BIGINT NOT NULL AUTO_INCREMENT,
    user_id  BIGINT NOT NULL,
    album_id BIGINT NOT NULL,
    rating   TINYINT NULL,
    liked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_listening UNIQUE (user_id, album_id),
    CONSTRAINT fk_album_listening_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_album_listening_album FOREIGN KEY (album_id) REFERENCES album (id) ON DELETE CASCADE,
    INDEX    idx_album_listening_album (album_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

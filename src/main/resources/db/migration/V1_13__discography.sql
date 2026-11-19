-- Spexify's albums are FGV's discography (fgv-infra-deploy docs/data-ownership.md): a released record has its catalogue
-- number, medium, dates, back cover and conductor here, and may be entered before its audio is digitised. Spexipedia's
-- recordings are gone; its spex pages show these albums. Nothing is live, so no data is carried over.

-- What a released album came out on, kept by admins like the album kinds
CREATE TABLE album_medium
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)      NOT NULL,
    name_sv          VARCHAR(100)  NOT NULL,
    name_en          VARCHAR(100)  NOT NULL,
    aliases          VARCHAR(2000) NOT NULL DEFAULT '',
    position         INT           NOT NULL,
    created_by       VARCHAR(64)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    last_modified_by VARCHAR(64)   NULL,
    last_modified_at DATETIME(6)   NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_medium_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO album_medium (uuid, name_sv, name_en, aliases, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000b001', 'LP', 'LP', 'lp\nvinyl\nlp-skiva\nlångspelare', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b002', 'EP', 'EP', 'ep\nep-skiva\nsingel\nsingle', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b003', 'CD', 'CD', 'cd\ncd-skiva', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b004', 'Digital', 'Digital', 'digital\nstreaming\nnedladdning', 4, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b005', 'Övrigt', 'Other', 'övrigt\nannat\nkassett\nother', 5, 'system', NOW(6));

ALTER TABLE album
    ADD COLUMN catalogue_number     VARCHAR(50)  NULL AFTER kind_id,
    ADD COLUMN medium_id            BIGINT       NULL AFTER catalogue_number,
    -- As precise as known: a year, a year and month, or a date
    ADD COLUMN released             VARCHAR(10)  NULL AFTER medium_id,
    ADD COLUMN recorded             VARCHAR(10)  NULL AFTER released,
    -- Free text, as on the record
    ADD COLUMN conductor            VARCHAR(255) NULL AFTER recorded,
    ADD COLUMN back_cover_extension VARCHAR(10)  NULL AFTER cover_extension,
    ADD CONSTRAINT fk_album_medium FOREIGN KEY (medium_id) REFERENCES album_medium (id),
    ADD INDEX idx_album_catalogue_number (catalogue_number);

-- A track of the discography may wait for its audio: no file until one is uploaded. A record's side (A, B) is optional.
ALTER TABLE track
    ADD COLUMN side VARCHAR(10) NULL AFTER disc_number,
    MODIFY COLUMN file_extension VARCHAR(10) NULL,
    MODIFY COLUMN file_format    VARCHAR(20) NULL,
    MODIFY COLUMN file_checksum  CHAR(64)    NULL,
    MODIFY COLUMN file_size      BIGINT      NOT NULL DEFAULT 0,
    MODIFY COLUMN duration_ms    BIGINT      NOT NULL DEFAULT 0;

-- A track that is a recording of a Spexipedia number takes the melody and the credits from the number, so its own
-- free-text credits go (they are kept only for tracks without a number)
UPDATE track SET melody = NULL, lyrics_author = NULL, arranger = NULL WHERE number_id IS NOT NULL;

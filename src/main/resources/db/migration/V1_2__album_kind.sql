-- Album kinds are configured by admins instead of being fixed. The first by position is the default, used for new
-- albums and not shown as a badge.
CREATE TABLE album_kind
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    name_sv          VARCHAR(100) NOT NULL,
    name_en          VARCHAR(100) NOT NULL,
    position         INT          NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_album_kind_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO album_kind (uuid, name_sv, name_en, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000a001', 'Föreställning', 'Show', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000a002', 'Studio', 'Studio', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000a003', 'Samling', 'Compilation', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000a004', 'Övrigt', 'Other', 4, 'system', NOW(6));

ALTER TABLE album
    ADD COLUMN kind_id BIGINT NULL AFTER kind;
UPDATE album a JOIN album_kind k ON k.position = CASE a.kind
                                                     WHEN 'STUDIO' THEN 2
                                                     WHEN 'COMPILATION' THEN 3
                                                     WHEN 'OTHER' THEN 4
                                                     ELSE 1 END
SET a.kind_id = k.id;
ALTER TABLE album
    MODIFY kind_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_album_kind FOREIGN KEY (kind_id) REFERENCES album_kind (id),
    DROP COLUMN kind;

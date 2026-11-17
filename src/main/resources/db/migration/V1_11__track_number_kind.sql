-- The kind of the number a track is a recording of (overture, finale…), copied from Spexipedia like the number's
-- title, for the start page's shelves per number kind and the kind's icon in track lists.
ALTER TABLE track
    ADD COLUMN number_kind_id CHAR(36) NULL,
    ADD INDEX idx_track_number_kind (number_kind_id);

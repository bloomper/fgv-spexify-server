-- Each track's loudness as EBU R 128 measures it, for ReplayGain. Null until measured; the LOUDNESS_SCAN job measures
-- the tracks uploaded before this.
ALTER TABLE track
    ADD COLUMN loudness_lufs  DOUBLE NULL AFTER bitrate,
    ADD COLUMN true_peak_dbfs DOUBLE NULL AFTER loudness_lufs;

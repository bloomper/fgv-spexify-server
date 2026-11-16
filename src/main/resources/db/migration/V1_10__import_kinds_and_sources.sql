-- Other spellings of an album kind that the import recognises, one per line (Spexipedia's docs/content-import.md, 3)
ALTER TABLE album_kind
    ADD COLUMN aliases VARCHAR(2000) NOT NULL DEFAULT '' AFTER name_en;

-- The SHA-256 of the file an import brought, before conversion, so that importing it again changes nothing
ALTER TABLE track
    ADD COLUMN source_checksum CHAR(64) NULL AFTER file_checksum;

-- Aliases for the default kinds, matching the folders of FGV's file archive; admins change them as they like
UPDATE album_kind SET aliases = 'föreställning\nshow\nliveinspelning\nljudinspelning\noriginalföreställning\nnyuppsättning\ngenrep' WHERE uuid = '00000000-0000-7000-8000-00000000a001';
UPDATE album_kind SET aliases = 'studio\nstudioinspelning\nskiva\nskivan\ncd\ncd-skiva\ninternskiva' WHERE uuid = '00000000-0000-7000-8000-00000000a002';
UPDATE album_kind SET aliases = 'samling\nsamlingsalbum\ncompilation' WHERE uuid = '00000000-0000-7000-8000-00000000a003';
UPDATE album_kind SET aliases = 'övrigt\nannat\nother' WHERE uuid = '00000000-0000-7000-8000-00000000a004';

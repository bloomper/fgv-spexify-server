-- "Samling" clashed with album collections ("Samlingar"); rename the default kind unless an admin already has.
UPDATE album_kind
SET name_sv = 'Samlingsalbum'
WHERE uuid = '00000000-0000-7000-8000-00000000a003'
  AND name_sv = 'Samling';

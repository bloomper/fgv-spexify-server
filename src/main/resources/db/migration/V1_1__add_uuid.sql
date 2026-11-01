-- Every entity that leaves the system is known by a stable external id; the BIGINT id stays internal. Albums and
-- tracks already have one.
ALTER TABLE app_user
    ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE app_user
SET uuid = UUID();
ALTER TABLE app_user MODIFY uuid CHAR (36) NOT NULL, ADD CONSTRAINT uk_app_user_uuid UNIQUE (uuid);

ALTER TABLE app_password
    ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE app_password
SET uuid = UUID();
ALTER TABLE app_password MODIFY uuid CHAR (36) NOT NULL, ADD CONSTRAINT uk_app_password_uuid UNIQUE (uuid);

ALTER TABLE api_key
    ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE api_key
SET uuid = UUID();
ALTER TABLE api_key MODIFY uuid CHAR (36) NOT NULL, ADD CONSTRAINT uk_api_key_uuid UNIQUE (uuid);

-- Spexregister's spex and categories are referred to by their external ids. The numeric ids cannot be mapped to them,
-- so the links are cleared and set again by hand or from fresh sample data.
UPDATE album
SET spex_id          = NULL,
    spex_category_id = NULL;
ALTER TABLE album MODIFY spex_id CHAR (36) NULL, MODIFY spex_category_id CHAR (36) NULL;

ALTER TABLE audit_entry MODIFY entity_id VARCHAR (36) NULL;
UPDATE audit_entry e JOIN album a
ON e.entity_type = 'ALBUM' AND e.entity_id = CAST (a.id AS CHAR) SET e.entity_id = a.uuid;
UPDATE audit_entry e JOIN track t
ON e.entity_type = 'TRACK' AND e.entity_id = CAST (t.id AS CHAR) SET e.entity_id = t.uuid;
UPDATE audit_entry e JOIN app_user u
ON e.entity_type = 'USER' AND e.entity_id = CAST (u.id AS CHAR) SET e.entity_id = u.uuid;

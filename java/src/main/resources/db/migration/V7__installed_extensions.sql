-- The optional CSD groups installed in the region, by com.carddemo.cics.Extension id. The Db2
-- transaction-type extension is installed by default now that V6 holds its tables.

ALTER TABLE cics_region ADD COLUMN installed text[] NOT NULL DEFAULT '{}';

UPDATE cics_region SET installed = '{transaction-type-db2}';

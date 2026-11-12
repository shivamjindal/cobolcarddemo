-- USRSEC kept clear-text X(08) passwords. Store bcrypt hashes of the upper-cased password instead,
-- and upper-case IDs, matching the case rule in com.carddemo.users.Credentials.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE users ALTER COLUMN sec_usr_pwd TYPE varchar(60);

UPDATE users SET sec_usr_id = upper(sec_usr_id), sec_usr_pwd = crypt(upper(sec_usr_pwd), gen_salt('bf', 10));

ALTER TABLE users
    ADD CONSTRAINT users_sec_usr_pwd_bcrypt CHECK (sec_usr_pwd ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'),
    ADD CONSTRAINT users_sec_usr_id_upper CHECK (sec_usr_id = upper(sec_usr_id));

COMMENT ON COLUMN users.sec_usr_pwd IS 'bcrypt hash of the upper-cased password (Credentials.hash), not the X(08) clear text';

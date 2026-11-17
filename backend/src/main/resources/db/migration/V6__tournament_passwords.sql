ALTER TABLE tournaments ADD COLUMN password_salt VARCHAR(64);
ALTER TABLE tournaments ADD COLUMN password_hash VARCHAR(64);

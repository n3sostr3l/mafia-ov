ALTER TABLE tournaments DROP COLUMN owner_id;
ALTER TABLE games DROP COLUMN owner_id;
ALTER TABLE uploads DROP COLUMN owner_id;
CREATE INDEX idx_tournaments_created_at ON tournaments(created_at);

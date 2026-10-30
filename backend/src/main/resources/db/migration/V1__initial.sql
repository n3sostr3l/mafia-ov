CREATE TABLE studio_users (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(40) NOT NULL UNIQUE,
 email VARCHAR(254) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 verified BOOLEAN NOT NULL DEFAULT FALSE, verify_hash VARCHAR(64), verify_expires TIMESTAMP WITH TIME ZONE
);
CREATE TABLE tournaments (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL REFERENCES studio_users(id),
 name VARCHAR(150) NOT NULL, external_id VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE games (
 id VARCHAR(36) PRIMARY KEY, tournament_id VARCHAR(36) NOT NULL REFERENCES tournaments(id),
 owner_id VARCHAR(36) NOT NULL REFERENCES studio_users(id), table_number INTEGER NOT NULL,
 game_number INTEGER NOT NULL, overlay_token VARCHAR(64) NOT NULL UNIQUE,
 state_json TEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tournament_id, table_number, game_number)
);
CREATE TABLE game_events (
 id VARCHAR(36) PRIMARY KEY, game_id VARCHAR(36) NOT NULL REFERENCES games(id),
 revision BIGINT NOT NULL, description VARCHAR(200) NOT NULL, state_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE uploads (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL REFERENCES studio_users(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_tournaments_owner ON tournaments(owner_id);
CREATE INDEX idx_games_owner ON games(owner_id);
CREATE INDEX idx_events_game ON game_events(game_id, revision);

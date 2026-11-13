CREATE TABLE tournament_players (
  id VARCHAR(36) PRIMARY KEY,
  tournament_id VARCHAR(36) NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
  name VARCHAR(50) NOT NULL,
  normalized_name VARCHAR(50) NOT NULL,
  photo_id VARCHAR(36) REFERENCES uploads(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_tournament_player_name UNIQUE (tournament_id, normalized_name)
);

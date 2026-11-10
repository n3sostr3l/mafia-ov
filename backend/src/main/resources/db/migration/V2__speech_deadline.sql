ALTER TABLE games ADD COLUMN speech_deadline TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_games_speech_deadline ON games(speech_deadline);

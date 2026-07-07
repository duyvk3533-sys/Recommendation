-- Migration V34: Add sentiment analysis support for reviews
ALTER TABLE reviews ADD COLUMN sentiment VARCHAR(20) DEFAULT 'NEUTRAL';

-- Retroactive sentiment classification based on existing rating stars
UPDATE reviews SET sentiment = 'POSITIVE' WHERE rating_star >= 4;
UPDATE reviews SET sentiment = 'NEGATIVE' WHERE rating_star <= 2;
UPDATE reviews SET sentiment = 'NEUTRAL' WHERE rating_star = 3;

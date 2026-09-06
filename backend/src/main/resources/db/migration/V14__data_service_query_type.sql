ALTER TABLE query_history
  MODIFY COLUMN query_type ENUM('adhoc', 'report', 'data_service') NOT NULL;

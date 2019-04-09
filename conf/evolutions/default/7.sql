# --- !Ups
ALTER TABLE application_extra
  ADD COLUMN decision_sended_date timestamp with time zone NULL;

# --- !Downs
ALTER TABLE application_extra
  DROP COLUMN decision_sended;
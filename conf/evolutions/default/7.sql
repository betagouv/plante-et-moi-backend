# --- !Ups
ALTER TABLE application_extra
  ADD COLUMN decision_sended_date timestamp with time zone NULL;
UPDATE application_extra SET decision_sended_date = '2019-01-01' WHERE status = 'Favorable' OR status = 'Défavorable';

# --- !Downs
ALTER TABLE application_extra
  DROP COLUMN decision_sended;
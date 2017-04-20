# --- !Ups
ALTER TABLE application_extra
    ADD COLUMN reviewer_agent_ids character varying(250)[] NOT NULL DEFAULT '{}';

# --- !Downs
ALTER TABLE application_extra
    DROP COLUMN reviewer_agent_ids;
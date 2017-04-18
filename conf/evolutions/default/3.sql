# --- !Ups
CREATE TABLE file (
    id character varying(100) NOT NULL,
    application_id character varying(100) NOT NULL,
    agent_id character varying(100) NULL,
    city character varying(100) NOT NULL,
    creation_date date NOT NULL,
    name character varying(255) NOT NULL,
    type character varying(255) NULL,
    data bytea NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE application_extra
    ADD COLUMN files jsonb NOT NULL DEFAULT '[]';

# --- !Downs
ALTER TABLE application_extra
    DROP COLUMN files;
DROP TABLE file;
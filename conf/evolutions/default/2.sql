# --- !Ups
CREATE TABLE comment (
    id character varying(100) NOT NULL,
    application_id character varying(100) NOT NULL,
    agent_id character varying(100) NOT NULL,
    city character varying(100) NOT NULL,
    creation_date date NOT NULL,
    comment text NOT NULL,
    PRIMARY KEY (id)
);

# --- !Downs
DROP TABLE comment;
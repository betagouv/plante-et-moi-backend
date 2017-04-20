# --- !Ups
CREATE TABLE email_sent (
    id character varying(100) NOT NULL,
    application_id character varying(100) NOT NULL,
    agent_id character varying(100) NULL,
    city character varying(100) NOT NULL,
    creation_date date NOT NULL,
    type character varying(255) NOT NULL,
    subject text NOT NULL,
    sent_from character varying(250) NOT NULL,
    sent_to character varying(250)[] NOT NULL,
    body_text text NOT NULL,
    reply_to character varying(150) NULL,
    PRIMARY KEY (id)
);

# --- !Downs
DROP TABLE email_sent;
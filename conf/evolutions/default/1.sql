# --- !Ups
CREATE TABLE review (
    application_id character varying(100) NOT NULL,
    agent_id character varying(100) NOT NULL,
    creation_date date NOT NULL,
    favorable boolean NOT NULL,
    comment text NOT NULL,
    PRIMARY KEY (application_id, agent_id)
);

CREATE TABLE application_imported (
    id character varying(100) NOT NULL,
    city character varying(100) NOT NULL,
    applicant_firstname character varying(100) NOT NULL,
    applicant_lastname character varying(100) NOT NULL,
    applicant_email character varying(150) NOT NULL,
    applicant_address character varying(500) NULL,
    type character varying(50) NOT NULL,
    address character varying(500) NOT NULL,
    creation_date date NOT NULL,
    coordinates point NOT NULL,
    source character varying(50) NOT NULL,
    source_id character varying(50) NOT NULL,
    applicant_phone character varying(50) NULL,
    fields json NOT NULL,
    files json NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE application_extra (
    application_id character varying(100) NOT NULL,
    status character varying(100) NOT NULL,
    PRIMARY KEY (application_id)
);

CREATE TABLE setting (
    key character varying(100) NOT NULL,
    city character varying(100) NOT NULL,
    value json NOT NULL,
    PRIMARY KEY (key, city)
);

# --- !Downs
DROP TABLE application_extra;
DROP TABLE application_imported;
DROP TABLE review;
DROP TABLE setting;

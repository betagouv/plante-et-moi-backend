# --- !Ups
ALTER TABLE application_extra
  ADD COLUMN applicant_firstname character varying(100) NULL,
  ADD COLUMN applicant_lastname character varying(100) NULL,
  ADD COLUMN applicant_email character varying(150) NULL,
  ADD COLUMN applicant_address character varying(500) NULL,
  ADD COLUMN applicant_phone character varying(50) NULL;


# --- !Downs
ALTER TABLE application_extra
  DROP COLUMN applicant_firstname,
  DROP COLUMN applicant_lastname,
  DROP COLUMN applicant_email,
  DROP COLUMN applicant_address,
  DROP COLUMN applicant_phone;
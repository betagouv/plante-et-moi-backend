# --- !Ups
ALTER TABLE application_imported ALTER creation_date TYPE timestamp with time zone;
ALTER TABLE review ALTER creation_date TYPE timestamp with time zone;
ALTER TABLE comment ALTER creation_date TYPE timestamp with time zone;
ALTER TABLE file ALTER creation_date TYPE timestamp with time zone;
ALTER TABLE email_sent ALTER creation_date TYPE timestamp with time zone;

# --- !Downs
ALTER TABLE application_imported ALTER creation_date TYPE date;
ALTER TABLE review ALTER creation_date TYPE date;
ALTER TABLE comment ALTER creation_date TYPE date;
ALTER TABLE file ALTER creation_date TYPE date;
ALTER TABLE email_sent ALTER creation_date TYPE date;
-- Giống deploy/postgres/init: tài khoản ứng dụng không phải owner, không vượt được RLS.
create role camhub_app login password 'camhub_app' nosuperuser nobypassrls;

-- Project-local backend role. No password is stored or generated in this script.
-- Assign its real password through the deployment secret-management process.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='arequipa_backend') THEN
        CREATE ROLE arequipa_backend LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END $$;

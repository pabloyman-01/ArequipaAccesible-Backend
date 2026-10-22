-- On Supabase enforce the Auth UUID link. Standalone PostgreSQL has no auth schema.
DO $$
BEGIN
    IF to_regclass('auth.users') IS NOT NULL THEN
        ALTER TABLE accesible.usuarios ADD CONSTRAINT usuarios_auth_fk
            FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE RESTRICT;
    END IF;
END $$;

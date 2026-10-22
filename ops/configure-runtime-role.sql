-- Execute as schema owner. The existing runtime role must be LOGIN, NOSUPERUSER,
-- NOBYPASSRLS and distinct from the migration owner. Password managed out of band.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE runtime_input(name text) ON COMMIT DROP;
INSERT INTO runtime_input VALUES (:'runtime_role');
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles r JOIN runtime_input i ON r.rolname=i.name
                   WHERE NOT r.rolsuper AND NOT r.rolbypassrls AND r.rolcanlogin)
       OR EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid=n.nspowner
                  JOIN runtime_input i ON r.rolname=i.name WHERE n.nspname='accesible') THEN
        RAISE EXCEPTION 'Use a distinct nonprivileged LOGIN runtime role';
    END IF;
END $$;
GRANT USAGE ON SCHEMA accesible TO :"runtime_role";
GRANT SELECT ON accesible.usuarios, accesible.roles, accesible.usuario_roles,
                accesible.distritos, accesible.tipos_barrera TO :"runtime_role";
GRANT INSERT ON accesible.usuarios, accesible.usuario_roles TO :"runtime_role";
GRANT UPDATE(nombres,apellidos,telefono,distrito_id) ON accesible.usuarios TO :"runtime_role";
DROP POLICY IF EXISTS backend_read ON accesible.usuarios;
CREATE POLICY backend_read ON accesible.usuarios FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_create ON accesible.usuarios;
CREATE POLICY backend_create ON accesible.usuarios FOR INSERT TO :"runtime_role"
    WITH CHECK (estado_cuenta='ACTIVA' AND estado_verificacion='NO_VERIFICADO');
DROP POLICY IF EXISTS backend_update ON accesible.usuarios;
CREATE POLICY backend_update ON accesible.usuarios FOR UPDATE TO :"runtime_role"
    USING (estado_cuenta='ACTIVA') WITH CHECK (estado_cuenta='ACTIVA');
DROP POLICY IF EXISTS backend_read ON accesible.roles;
CREATE POLICY backend_read ON accesible.roles FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_read ON accesible.usuario_roles;
CREATE POLICY backend_read ON accesible.usuario_roles FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_citizen ON accesible.usuario_roles;
CREATE POLICY backend_citizen ON accesible.usuario_roles FOR INSERT TO :"runtime_role"
    WITH CHECK (rol_id IN (SELECT id FROM accesible.roles WHERE codigo='CIUDADANO'));
DROP POLICY IF EXISTS backend_read ON accesible.distritos;
CREATE POLICY backend_read ON accesible.distritos FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_read ON accesible.tipos_barrera;
CREATE POLICY backend_read ON accesible.tipos_barrera FOR SELECT TO :"runtime_role" USING (true);
COMMIT;

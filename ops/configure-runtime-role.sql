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
-- HU-01: server-side access only, never anon/authenticated. Administration grants follow below.
GRANT SELECT, INSERT ON accesible.reportes, accesible.reporte_fotos, accesible.historial_estados TO :"runtime_role";
GRANT INSERT ON accesible.auditoria TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE ON accesible.contadores_reporte, accesible.reporte_limites TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON accesible.archivo_cargas TO :"runtime_role";
GRANT USAGE ON SEQUENCE accesible.reportes_id_seq, accesible.reporte_fotos_id_seq,
    accesible.historial_estados_id_seq, accesible.auditoria_id_seq TO :"runtime_role";
DROP POLICY IF EXISTS backend_read ON accesible.reportes;
CREATE POLICY backend_read ON accesible.reportes FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_create ON accesible.reportes;
CREATE POLICY backend_create ON accesible.reportes FOR INSERT TO :"runtime_role" WITH CHECK (estado='RECIBIDO' AND canal='WEB');
DROP POLICY IF EXISTS backend_read ON accesible.reporte_fotos;
CREATE POLICY backend_read ON accesible.reporte_fotos FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_create ON accesible.reporte_fotos;
CREATE POLICY backend_create ON accesible.reporte_fotos FOR INSERT TO :"runtime_role" WITH CHECK (clase_foto='EVIDENCIA');
DROP POLICY IF EXISTS backend_read ON accesible.historial_estados;
CREATE POLICY backend_read ON accesible.historial_estados FOR SELECT TO :"runtime_role" USING (true);
DROP POLICY IF EXISTS backend_create ON accesible.historial_estados;
CREATE POLICY backend_create ON accesible.historial_estados FOR INSERT TO :"runtime_role"
    WITH CHECK (estado_anterior IS NULL AND estado_nuevo='RECIBIDO');
DROP POLICY IF EXISTS backend_create ON accesible.auditoria;
CREATE POLICY backend_create ON accesible.auditoria FOR INSERT TO :"runtime_role"
    WITH CHECK (accion='CREAR_REPORTE' AND entidad='reportes');
DROP POLICY IF EXISTS backend_access ON accesible.contadores_reporte;
CREATE POLICY backend_access ON accesible.contadores_reporte TO :"runtime_role" USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS backend_access ON accesible.reporte_limites;
CREATE POLICY backend_access ON accesible.reporte_limites TO :"runtime_role" USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS backend_access ON accesible.archivo_cargas;
CREATE POLICY backend_access ON accesible.archivo_cargas TO :"runtime_role" USING (true) WITH CHECK (true);
-- Administration: only these columns can change; no role grants or deletion.
GRANT UPDATE(estado,fecha_actualizacion) ON accesible.reportes TO :"runtime_role";
GRANT UPDATE(estado_cuenta) ON accesible.usuarios TO :"runtime_role";
DO $admin$
DECLARE runtime_name text;
admin_check text := $check$EXISTS (
  SELECT 1 FROM accesible.usuarios au
  JOIN accesible.usuario_roles aur ON aur.usuario_id=au.id
  JOIN accesible.roles ar ON ar.id=aur.rol_id
  WHERE au.id=NULLIF(current_setting('app.actor_id',true),'')::uuid
    AND au.estado_cuenta='ACTIVA' AND ar.codigo='ADMINISTRADOR'
)$check$;
BEGIN
  SELECT name INTO runtime_name FROM runtime_input;
  EXECUTE format('DROP POLICY IF EXISTS backend_admin_update ON accesible.reportes');
  EXECUTE format('CREATE POLICY backend_admin_update ON accesible.reportes FOR UPDATE TO %I USING (%s) WITH CHECK (%s)',runtime_name,admin_check,admin_check);
  EXECUTE format('DROP POLICY IF EXISTS backend_admin_update ON accesible.usuarios');
  EXECUTE format('CREATE POLICY backend_admin_update ON accesible.usuarios FOR UPDATE TO %I USING (%s) WITH CHECK (%s)',runtime_name,admin_check,admin_check);
  EXECUTE format('DROP POLICY IF EXISTS backend_admin_history ON accesible.historial_estados');
  EXECUTE format($policy$CREATE POLICY backend_admin_history ON accesible.historial_estados FOR INSERT TO %I WITH CHECK (
    %s AND actor_id=NULLIF(current_setting('app.actor_id',true),'')::uuid
    AND (estado_anterior,estado_nuevo) IN (
      ('RECIBIDO','EN_EVALUACION'),('EN_EVALUACION','EN_PROCESO'),('EN_EVALUACION','RECHAZADO'),
      ('ASIGNADO','EN_PROCESO'),('EN_PROCESO','ATENDIDO'))
    AND (estado_nuevo<>'RECHAZADO' OR NULLIF(btrim(motivo),'') IS NOT NULL))$policy$,runtime_name,admin_check);
  EXECUTE format('DROP POLICY IF EXISTS backend_admin_audit ON accesible.auditoria');
  EXECUTE format($policy$CREATE POLICY backend_admin_audit ON accesible.auditoria FOR INSERT TO %I WITH CHECK (
    %s AND actor_id=NULLIF(current_setting('app.actor_id',true),'')::uuid AND (
      (accion='CAMBIAR_ESTADO_REPORTE' AND entidad='reportes') OR
      (accion='CAMBIAR_ESTADO_USUARIO' AND entidad='usuarios')))$policy$,runtime_name,admin_check);
END $admin$;
COMMIT;

-- psql only. Run as a trusted database operator; no credentials in the command line.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path = accesible, pg_catalog;
-- Both operator actor and target must have existing active profiles.
SELECT id FROM usuarios WHERE id IN (:'actor_id'::uuid, :'usuario_id'::uuid) ORDER BY id FOR UPDATE;
CREATE TEMP TABLE admin_input(actor uuid, target uuid, reason text) ON COMMIT DROP;
INSERT INTO admin_input VALUES (:'actor_id'::uuid, :'usuario_id'::uuid, :'motivo');
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM usuarios u JOIN admin_input i ON u.id=i.actor WHERE estado_cuenta='ACTIVA')
       OR NOT EXISTS (SELECT 1 FROM usuarios u JOIN admin_input i ON u.id=i.target WHERE estado_cuenta='ACTIVA')
       OR EXISTS (SELECT 1 FROM admin_input WHERE length(trim(reason)) < 10) THEN
        RAISE EXCEPTION 'Active actor/target and reason of at least 10 characters required';
    END IF;
END $$;
WITH granted AS (
    INSERT INTO usuario_roles(usuario_id,rol_id)
    SELECT i.target,r.id FROM admin_input i CROSS JOIN roles r WHERE r.codigo='ADMINISTRADOR'
    ON CONFLICT DO NOTHING RETURNING usuario_id
)
INSERT INTO auditoria(actor_id,accion,entidad,entidad_id,metadatos)
SELECT i.actor,'OTORGAR_ADMINISTRADOR','usuarios',g.usuario_id::text,
       jsonb_build_object('motivo',i.reason,'operador_db',session_user)
FROM granted g CROSS JOIN admin_input i;
COMMIT;

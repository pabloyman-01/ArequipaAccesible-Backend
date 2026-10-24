CREATE TABLE contadores_reporte (
    anio integer PRIMARY KEY CHECK (anio BETWEEN 2000 AND 9999),
    ultimo bigint NOT NULL CHECK (ultimo BETWEEN 1 AND 999999)
);
CREATE TABLE reportes (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo varchar(15) NOT NULL UNIQUE CHECK (codigo ~ '^ARQ-[0-9]{4}-[0-9]{6}$'),
    ciudadano_id uuid NOT NULL REFERENCES usuarios(id),
    tipo_barrera_id bigint NOT NULL REFERENCES tipos_barrera(id),
    distrito_id bigint NOT NULL REFERENCES distritos(id),
    severidad_declarada varchar(10) NOT NULL CHECK (severidad_declarada IN ('BAJA','MEDIA','ALTA')),
    descripcion varchar(3000) NOT NULL CHECK (length(trim(descripcion)) > 0),
    direccion_referencia varchar(500) NOT NULL CHECK (length(trim(direccion_referencia)) > 0),
    latitud numeric(10,7) NOT NULL CHECK (latitud BETWEEN -90 AND 90),
    longitud numeric(10,7) NOT NULL CHECK (longitud BETWEEN -180 AND 180),
    estado varchar(20) NOT NULL DEFAULT 'RECIBIDO'
        CHECK (estado IN ('RECIBIDO','EN_EVALUACION','ASIGNADO','EN_PROCESO','ATENDIDO','RECHAZADO')),
    canal varchar(20) NOT NULL DEFAULT 'WEB' CHECK (canal='WEB'),
    fecha_creacion timestamptz NOT NULL,
    fecha_actualizacion timestamptz NOT NULL
);
CREATE INDEX reportes_ciudadano_fecha_idx ON reportes(ciudadano_id, fecha_creacion DESC);
CREATE INDEX reportes_tipo_idx ON reportes(tipo_barrera_id);
CREATE INDEX reportes_distrito_estado_idx ON reportes(distrito_id, estado);
CREATE TABLE reporte_fotos (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporte_id bigint NOT NULL REFERENCES reportes(id),
    object_key varchar(200) NOT NULL UNIQUE,
    mime_type varchar(20) NOT NULL CHECK (mime_type IN ('image/jpeg','image/png')),
    tamano_bytes bigint NOT NULL CHECK (tamano_bytes BETWEEN 1 AND 5242880),
    clase_foto varchar(20) NOT NULL DEFAULT 'EVIDENCIA' CHECK (clase_foto='EVIDENCIA'),
    creado_en timestamptz NOT NULL
);
CREATE INDEX reporte_fotos_reporte_idx ON reporte_fotos(reporte_id);
CREATE TABLE historial_estados (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporte_id bigint NOT NULL REFERENCES reportes(id),
    estado_anterior varchar(20),
    estado_nuevo varchar(20) NOT NULL CHECK (estado_nuevo IN ('RECIBIDO','EN_EVALUACION','ASIGNADO','EN_PROCESO','ATENDIDO','RECHAZADO')),
    motivo varchar(500),
    actor_id uuid NOT NULL REFERENCES usuarios(id),
    fecha_cambio timestamptz NOT NULL
);
CREATE INDEX historial_reporte_fecha_idx ON historial_estados(reporte_id, fecha_cambio);
CREATE INDEX historial_actor_idx ON historial_estados(actor_id);
-- Durable upload intent exists BEFORE the external HTTP call. Success removes it
-- atomically with report/photo/history/audit, so a crash leaves recoverable cleanup.
CREATE TABLE archivo_cargas (
    id uuid PRIMARY KEY,
    object_key varchar(200) NOT NULL UNIQUE,
    creado_en timestamptz NOT NULL DEFAULT now(),
    limpiar_despues timestamptz NOT NULL DEFAULT now() + interval '30 minutes',
    intentos integer NOT NULL DEFAULT 0 CHECK (intentos >= 0)
);
CREATE INDEX archivo_cargas_pendientes_idx ON archivo_cargas(limpiar_despues);
CREATE TABLE reporte_limites (
    usuario_id uuid PRIMARY KEY REFERENCES usuarios(id),
    ventana timestamptz NOT NULL,
    solicitudes integer NOT NULL CHECK (solicitudes > 0)
);
REVOKE ALL ON ALL TABLES IN SCHEMA accesible FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA accesible FROM PUBLIC;
ALTER TABLE contadores_reporte ENABLE ROW LEVEL SECURITY;
ALTER TABLE reportes ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporte_fotos ENABLE ROW LEVEL SECURITY;
ALTER TABLE historial_estados ENABLE ROW LEVEL SECURITY;
ALTER TABLE archivo_cargas ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporte_limites ENABLE ROW LEVEL SECURITY;
DO $$ DECLARE client_role text; BEGIN
    FOREACH client_role IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=client_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA accesible FROM %I',client_role);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA accesible FROM %I',client_role);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA accesible FROM %I',client_role);
        END IF;
    END LOOP;
END $$;

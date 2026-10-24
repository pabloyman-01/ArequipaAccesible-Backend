CREATE TABLE roles (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo varchar(30) NOT NULL UNIQUE CHECK (codigo IN ('CIUDADANO','ADMINISTRADOR'))
);
INSERT INTO roles(codigo) VALUES ('CIUDADANO'), ('ADMINISTRADOR');
CREATE TABLE distritos (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre varchar(120) NOT NULL UNIQUE,
    activo boolean NOT NULL DEFAULT true
);
-- No official catalogue supplied: intentionally no invented districts or barrier types.
CREATE TABLE tipos_barrera (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo varchar(20) NOT NULL UNIQUE,
    nombre varchar(120) NOT NULL,
    descripcion text,
    activo boolean NOT NULL DEFAULT true
);
CREATE TABLE usuarios (
    id uuid PRIMARY KEY,
    nombres varchar(100) NOT NULL CHECK (length(trim(nombres)) > 0),
    apellidos varchar(100) NOT NULL CHECK (length(trim(apellidos)) > 0),
    tipo_documento varchar(20) NOT NULL CHECK (tipo_documento IN ('DNI','CE','PASAPORTE')),
    documento varchar(20) NOT NULL UNIQUE CHECK (length(trim(documento)) > 0),
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    telefono varchar(20),
    distrito_id bigint REFERENCES distritos(id),
    estado_cuenta varchar(20) NOT NULL DEFAULT 'ACTIVA' CHECK (estado_cuenta IN ('ACTIVA','SUSPENDIDA')),
    estado_verificacion varchar(20) NOT NULL DEFAULT 'NO_VERIFICADO'
        CHECK (estado_verificacion IN ('NO_VERIFICADO','PENDIENTE','VERIFICADO')),
    creado_en timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX usuarios_distrito_idx ON usuarios(distrito_id);
CREATE TABLE usuario_roles (
    usuario_id uuid NOT NULL REFERENCES usuarios(id),
    rol_id bigint NOT NULL REFERENCES roles(id),
    PRIMARY KEY(usuario_id, rol_id)
);
CREATE INDEX usuario_roles_rol_idx ON usuario_roles(rol_id);
CREATE TABLE auditoria (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id uuid REFERENCES usuarios(id),
    accion varchar(80) NOT NULL,
    entidad varchar(80) NOT NULL,
    entidad_id varchar(100) NOT NULL,
    metadatos jsonb NOT NULL DEFAULT '{}'::jsonb,
    fecha timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auditoria_actor_fecha_idx ON auditoria(actor_id, fecha);
-- Private backend-only schema. No Data API policies/grants to anon/authenticated.
REVOKE ALL ON SCHEMA accesible FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA accesible FROM PUBLIC;
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE distritos ENABLE ROW LEVEL SECURITY;
ALTER TABLE tipos_barrera ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuarios ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE auditoria ENABLE ROW LEVEL SECURITY;

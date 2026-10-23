package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.ReporteRepositoryPort;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.*;

@Repository
@Transactional
public class JpaReporteAdapter implements ReporteRepositoryPort {
    @PersistenceContext EntityManager em;
    @Override @Transactional(readOnly=true)
    public void validarCatalogos(CrearReporte d) { catalogos(d,false); }
    private void catalogos(CrearReporte d, boolean bloquear) {
        String lock = ""; // Catalogues are read-only for the runtime role; rechecked inside create's transaction.
        if (em.createNativeQuery("SELECT id FROM accesible.distritos WHERE activo AND id=:id"+lock)
                .setParameter("id",d.distritoId()).getResultList().isEmpty()
                || em.createNativeQuery("SELECT id FROM accesible.tipos_barrera WHERE activo AND id=:id"+lock)
                .setParameter("id",d.tipoBarreraId()).getResultList().isEmpty()) throw new ReporteException(INVALIDO);
    }
    private void ciudadanoActivo(UUID id) {
        var estado = em.createNativeQuery("SELECT estado_cuenta FROM accesible.usuarios WHERE id=:id FOR SHARE")
                .setParameter("id",id).getResultList();
        var roles = em.createNativeQuery("""
            SELECT r.codigo FROM accesible.usuario_roles ur JOIN accesible.roles r ON r.id=ur.rol_id
            WHERE ur.usuario_id=:id AND r.codigo='CIUDADANO'
            """).setParameter("id",id).getResultList();
        if (estado.isEmpty() || !"ACTIVA".equals(estado.getFirst()) || roles.isEmpty()) throw new ReporteException(SIN_PERMISO);
    }
    @Override public UUID reservarCarga(UUID actor, String key, int limite) {
        ciudadanoActivo(actor);
        Number count = (Number)em.createNativeQuery("""
            INSERT INTO accesible.reporte_limites(usuario_id,ventana,solicitudes)
            VALUES (:id,date_trunc('minute',now()),1)
            ON CONFLICT(usuario_id) DO UPDATE SET ventana=excluded.ventana,
              solicitudes=CASE WHEN reporte_limites.ventana=excluded.ventana THEN reporte_limites.solicitudes+1 ELSE 1 END
            RETURNING solicitudes
            """).setParameter("id",actor).getSingleResult();
        if (count.intValue() > limite) throw new ReporteException(LIMITE_EXCEDIDO);
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO accesible.archivo_cargas(id,object_key) VALUES (:id,:key)")
                .setParameter("id",id).setParameter("key",key).executeUpdate();
        return id;
    }
    @Override public Reporte crear(CrearReporte d, UUID actor, UUID carga, ImagenValidada imagen, Instant fecha) {
        var keys = em.createNativeQuery("SELECT object_key FROM accesible.archivo_cargas WHERE id=:id FOR UPDATE")
                .setParameter("id",carga).getResultList();
        if (keys.isEmpty()) throw new ReporteException(CONFLICTO);
        ciudadanoActivo(actor);
        catalogos(d,true);
        int anio = fecha.atZone(ZoneId.of("America/Lima")).getYear();
        var secuencia = em.createNativeQuery("""
            INSERT INTO accesible.contadores_reporte(anio,ultimo) VALUES (:anio,1)
            ON CONFLICT(anio) DO UPDATE SET ultimo=contadores_reporte.ultimo+1
            WHERE contadores_reporte.ultimo < 999999 RETURNING ultimo
            """).setParameter("anio",anio).getResultList();
        if (secuencia.isEmpty()) throw new ReporteException(CONFLICTO);
        var r = new ReporteEntity();
        r.codigo = "ARQ-%04d-%06d".formatted(anio,((Number)secuencia.getFirst()).longValue());
        r.ciudadanoId=actor; r.tipoBarreraId=d.tipoBarreraId(); r.distritoId=d.distritoId();
        r.severidad=d.severidadDeclarada().name(); r.descripcion=d.descripcion().strip(); r.direccion=d.direccionReferencia().strip();
        r.latitud=d.latitud(); r.longitud=d.longitud(); r.estado="RECIBIDO"; r.canal="WEB"; r.creado=fecha; r.actualizado=fecha;
        em.persist(r);
        var f = new FotoEntity();
        f.reporteId=r.id; f.objectKey=keys.getFirst().toString(); f.mime=imagen.mimeType();
        f.tamano=imagen.contenido().length; f.clase="EVIDENCIA"; f.creado=fecha;
        em.persist(f);
        em.createNativeQuery("""
            INSERT INTO accesible.historial_estados(reporte_id,estado_nuevo,actor_id,fecha_cambio)
            VALUES (:id,'RECIBIDO',:actor,:fecha)
            """).setParameter("id",r.id).setParameter("actor",actor).setParameter("fecha",fecha).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO accesible.auditoria(actor_id,accion,entidad,entidad_id)
            VALUES (:actor,'CREAR_REPORTE','reportes',:id)
            """).setParameter("actor",actor).setParameter("id",r.id.toString()).executeUpdate();
        em.createNativeQuery("DELETE FROM accesible.archivo_cargas WHERE id=:id").setParameter("id",carga).executeUpdate();
        em.flush();
        return map(r);
    }
    @Override @Transactional(readOnly=true)
    public Optional<Reporte> buscar(long id, UUID actor, boolean admin) {
        return em.createQuery("SELECT r FROM ReporteEntity r WHERE r.id=:id AND (r.ciudadanoId=:actor OR :admin=true)",ReporteEntity.class)
                .setParameter("id",id).setParameter("actor",actor).setParameter("admin",admin).getResultStream().findFirst().map(this::map);
    }
    private Reporte map(ReporteEntity r) {
        var fotos = em.createQuery("SELECT f FROM FotoEntity f WHERE f.reporteId=:id ORDER BY f.id",FotoEntity.class)
                .setParameter("id",r.id).getResultList().stream()
                .map(f -> new Reporte.Foto(f.id,f.objectKey,f.mime,f.tamano)).toList();
        return new Reporte(r.id,r.codigo,r.ciudadanoId,r.tipoBarreraId,r.distritoId,Severidad.valueOf(r.severidad),
                r.descripcion,r.direccion,r.latitud,r.longitud,r.estado,r.creado,fotos);
    }
    @Override @Transactional(readOnly=true)
    public Optional<List<CambioEstadoReporte>> historial(long id, UUID actor, boolean admin) {
        // Distinguish an authorized report with no history from a missing/foreign report.
        // Do not call buscar: history does not need photo metadata or Storage.
        var autorizado=em.createQuery("SELECT r.id FROM ReporteEntity r WHERE r.id=:id AND (r.ciudadanoId=:actor OR :admin=true)",Long.class)
                .setParameter("id",id).setParameter("actor",actor).setParameter("admin",admin).getResultList();
        if (autorizado.isEmpty()) return Optional.empty();
        var cambios=em.createQuery("""
            SELECT new pe.arequipa.accesible.domain.model.CambioEstadoReporte(
                h.id,h.estadoAnterior,h.estadoNuevo,h.fechaCambio,h.motivo)
            FROM HistorialEstadoEntity h, ReporteEntity r
            WHERE h.reporteId=r.id AND r.id=:id AND (r.ciudadanoId=:actor OR :admin=true)
            ORDER BY h.fechaCambio ASC,h.id ASC
            """,CambioEstadoReporte.class)
                .setParameter("id",id).setParameter("actor",actor).setParameter("admin",admin).getResultList();
        return Optional.of(cambios);
    }
    @Override @Transactional(readOnly=true)
    public List<ResumenReporte> listarPropios(UUID actor, ConsultaReportes filtro) {
        // Owner is mandatory for all callers, including citizens with an admin role.
        StringBuilder where=new StringBuilder(" WHERE r.ciudadanoId=:actor");
        Map<String,Object> parametros=new LinkedHashMap<>();
        parametros.put("actor",actor);
        if (filtro.estado()!=null) { where.append(" AND r.estado=:estado"); parametros.put("estado",filtro.estado().name()); }
        if (filtro.severidadDeclarada()!=null) { where.append(" AND r.severidad=:severidad"); parametros.put("severidad",filtro.severidadDeclarada().name()); }
        if (filtro.tipoBarreraId()!=null) { where.append(" AND r.tipoBarreraId=:tipo"); parametros.put("tipo",filtro.tipoBarreraId()); }
        if (filtro.distritoId()!=null) { where.append(" AND r.distritoId=:distrito"); parametros.put("distrito",filtro.distritoId()); }
        if (filtro.codigo()!=null) { where.append(" AND r.codigo=:codigo"); parametros.put("codigo",filtro.codigo()); }
        if (filtro.desde()!=null) { where.append(" AND r.creado>=:desde"); parametros.put("desde",filtro.desdeInclusivo()); }
        if (filtro.hasta()!=null) { where.append(" AND r.creado<:hasta"); parametros.put("hasta",filtro.hastaExclusivo()); }
        if (filtro.cursor()!=null) {
            // Explicit upper bound lets the existing date index start at the cursor.
            where.append(" AND r.creado<=:fechaCursor AND (r.creado<:fechaCursor OR r.id<:idCursor)");
            parametros.put("fechaCursor",filtro.cursor().fecha()); parametros.put("idCursor",filtro.cursor().id());
        }
        var query=em.createQuery("""
            SELECT new pe.arequipa.accesible.domain.model.ResumenReporte(
                r.id,r.codigo,r.tipoBarreraId,r.distritoId,r.severidad,r.descripcion,r.direccion,r.estado,r.creado)
            FROM ReporteEntity r
            """+where+" ORDER BY r.creado DESC,r.id DESC",ResumenReporte.class);
        parametros.forEach(query::setParameter);
        // One extra row establishes hayMas; no count query or per-report photo fetch.
        return query.setMaxResults(filtro.limite()+1).getResultList();
    }
    @Override public void limpiarCarga(UUID carga, boolean inmediata, Consumer<String> eliminar) {
        String expired = inmediata ? "" : " AND limpiar_despues <= now()";
        var rows = em.createNativeQuery("SELECT object_key FROM accesible.archivo_cargas WHERE id=:id"+expired+" FOR UPDATE")
                .setParameter("id",carga).getResultList();
        if (rows.isEmpty()) return;
        String key = rows.getFirst().toString();
        Number referencias = (Number)em.createNativeQuery("SELECT count(*) FROM accesible.reporte_fotos WHERE object_key=:key")
                .setParameter("key",key).getSingleResult();
        if (referencias.intValue()==0) {
            try { eliminar.accept(key); }
            catch (RuntimeException e) {
                em.createNativeQuery("""
                    UPDATE accesible.archivo_cargas SET intentos=intentos+1,
                    limpiar_despues=now()+interval '15 minutes' WHERE id=:id
                    """).setParameter("id",carga).executeUpdate();
                return;
            }
        }
        em.createNativeQuery("DELETE FROM accesible.archivo_cargas WHERE id=:id").setParameter("id",carga).executeUpdate();
    }
    @Override @Transactional(readOnly=true)
    public List<UUID> cargasVencidas() {
        List<?> rows = em.createNativeQuery("SELECT id FROM accesible.archivo_cargas WHERE limpiar_despues<=now() ORDER BY limpiar_despues LIMIT 20").getResultList();
        return rows.stream().map(id -> UUID.fromString(id.toString())).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<Catalogo> distritos() { return catalogo("SELECT id,NULL,nombre,NULL FROM accesible.distritos WHERE activo ORDER BY nombre"); }
    @Override @Transactional(readOnly=true)
    public List<Catalogo> tiposBarrera() { return catalogo("SELECT id,codigo,nombre,descripcion FROM accesible.tipos_barrera WHERE activo ORDER BY codigo"); }
    private List<Catalogo> catalogo(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(row -> { var a=(Object[])row; return new Catalogo(((Number)a[0]).longValue(),(String)a[1],(String)a[2],(String)a[3]); }).toList();
    }
}

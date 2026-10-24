package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.AdministracionRepositoryPort;
import static pe.arequipa.accesible.domain.model.AdministracionException.Motivo.*;

@Repository
@Transactional
public class JpaAdministracionAdapter implements AdministracionRepositoryPort {
    @PersistenceContext EntityManager em;
    private void contexto(UUID actor) {
        em.createNativeQuery("SELECT set_config('app.actor_id',:actor,true)").setParameter("actor",actor.toString()).getSingleResult();
    }
    private void administrador(UUID actor) {
        var filas=em.createNativeQuery("""
            SELECT u.id FROM accesible.usuarios u WHERE u.id=:actor AND u.estado_cuenta='ACTIVA'
              AND EXISTS (SELECT 1 FROM accesible.usuario_roles ur JOIN accesible.roles r ON r.id=ur.rol_id
                          WHERE ur.usuario_id=u.id AND r.codigo='ADMINISTRADOR') FOR SHARE
            """).setParameter("actor",actor).getResultList();
        if (filas.isEmpty()) throw new AdministracionException(SIN_PERMISO);
    }
    @Override public ReporteActualizado cambiarEstadoReporte(long id, TransicionReporte cambio, UUID actor) {
        contexto(actor);administrador(actor);
        var reporte=em.find(ReporteEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if (reporte==null) throw new AdministracionException(NO_ENCONTRADO);
        EstadoReporte anterior=EstadoReporte.valueOf(reporte.estado);cambio.validar(anterior);
        // Timestamp is taken after locking; preserve chronological order if the clock moves back.
        Instant fecha=Instant.now();if(fecha.isBefore(reporte.actualizado))fecha=reporte.actualizado;
        int changed=em.createNativeQuery("UPDATE accesible.reportes SET estado=:nuevo,fecha_actualizacion=:fecha WHERE id=:id AND estado=:anterior")
                .setParameter("nuevo",cambio.nuevoEstado().name()).setParameter("fecha",fecha)
                .setParameter("id",id).setParameter("anterior",anterior.name()).executeUpdate();
        if(changed!=1)throw new AdministracionException(ESTADO_CONFLICTO);
        em.createNativeQuery("""
            INSERT INTO accesible.historial_estados(reporte_id,estado_anterior,estado_nuevo,motivo,actor_id,fecha_cambio)
            VALUES (:id,:anterior,:nuevo,:motivo,:actor,:fecha)
            """).setParameter("id",id).setParameter("anterior",anterior.name()).setParameter("nuevo",cambio.nuevoEstado().name())
                .setParameter("motivo",cambio.motivo()).setParameter("actor",actor).setParameter("fecha",fecha).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO accesible.auditoria(actor_id,accion,entidad,entidad_id,metadatos,fecha)
            VALUES (:actor,'CAMBIAR_ESTADO_REPORTE','reportes',:id,
              jsonb_build_object('anterior',cast(:anterior as text),'nuevo',cast(:nuevo as text)),:fecha)
            """).setParameter("actor",actor).setParameter("id",Long.toString(id)).setParameter("anterior",anterior.name())
                .setParameter("nuevo",cambio.nuevoEstado().name()).setParameter("fecha",fecha).executeUpdate();
        return new ReporteActualizado(id,reporte.codigo,cambio.nuevoEstado(),fecha);
    }
    @Override @Transactional(readOnly=true)
    public List<ResumenUsuario> listarUsuarios(int limite, UUID cursor, EstadoCuenta estado, Rol rol, Long distrito) {
        StringBuilder sql=new StringBuilder("SELECT u.* FROM accesible.usuarios u WHERE 1=1");
        Map<String,Object> parametros=new LinkedHashMap<>();
        if(cursor!=null){sql.append(" AND u.id>:cursor");parametros.put("cursor",cursor);}
        if(estado!=null){sql.append(" AND u.estado_cuenta=:estado");parametros.put("estado",estado.name());}
        if(distrito!=null){sql.append(" AND u.distrito_id=:distrito");parametros.put("distrito",distrito);}
        if(rol!=null){sql.append(" AND EXISTS (SELECT 1 FROM accesible.usuario_roles ur JOIN accesible.roles r ON r.id=ur.rol_id WHERE ur.usuario_id=u.id AND r.codigo=:rol)");parametros.put("rol",rol.name());}
        var query=em.createNativeQuery(sql+" ORDER BY u.id ASC",UsuarioEntity.class);parametros.forEach(query::setParameter);
        @SuppressWarnings("unchecked") List<UsuarioEntity> usuarios=query.setMaxResults(limite+1).getResultList();
        if(usuarios.isEmpty())return List.of();
        List<?> asignaciones=em.createNativeQuery("SELECT ur.usuario_id,r.codigo FROM accesible.usuario_roles ur JOIN accesible.roles r ON r.id=ur.rol_id WHERE ur.usuario_id IN (:ids)")
                .setParameter("ids",usuarios.stream().map(u->u.id).toList()).getResultList();
        Map<UUID,Set<Rol>> roles=new HashMap<>();
        for(Object row:asignaciones){var a=(Object[])row;roles.computeIfAbsent(UUID.fromString(a[0].toString()),k->EnumSet.noneOf(Rol.class)).add(Rol.valueOf(a[1].toString()));}
        return usuarios.stream().map(u->new ResumenUsuario(u.id,u.nombres,u.apellidos,u.email,u.tipoDocumento,
                "*".repeat(Math.max(0,u.documento.length()-4))+u.documento.substring(Math.max(0,u.documento.length()-4)),
                u.estadoCuenta,u.estadoVerificacion,u.distritoId,roles.getOrDefault(u.id,Set.of()),u.creadoEn)).toList();
    }
    @Override public UsuarioActualizado cambiarEstadoUsuario(UUID id, CambioEstadoCuenta cambio, UUID actor) {
        contexto(actor);
        // Serialize administrative account changes, then lock users in UUID order.
        em.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(211004,1)").getSingleResult();
        em.createNativeQuery("SELECT id FROM accesible.usuarios WHERE id IN (:actor,:destino) ORDER BY id FOR UPDATE")
                .setParameter("actor",actor).setParameter("destino",id).getResultList();
        administrador(actor);
        var usuario=em.find(UsuarioEntity.class,id);
        if(usuario==null)throw new AdministracionException(NO_ENCONTRADO);
        if(id.equals(actor)&&cambio.nuevoEstado()==EstadoCuenta.SUSPENDIDA)throw new AdministracionException(ESTADO_CONFLICTO);
        if(!usuario.estadoCuenta.equals(cambio.estadoEsperado().name()))throw new AdministracionException(ESTADO_CONFLICTO);
        if(usuario.estadoCuenta.equals(cambio.nuevoEstado().name()))return new UsuarioActualizado(id,cambio.nuevoEstado());
        int changed=em.createNativeQuery("UPDATE accesible.usuarios SET estado_cuenta=:nuevo WHERE id=:id")
                .setParameter("nuevo",cambio.nuevoEstado().name()).setParameter("id",id).executeUpdate();
        if(changed!=1)throw new AdministracionException(ESTADO_CONFLICTO);
        em.createNativeQuery("""
            INSERT INTO accesible.auditoria(actor_id,accion,entidad,entidad_id,metadatos)
            VALUES (:actor,'CAMBIAR_ESTADO_USUARIO','usuarios',:id,
              jsonb_build_object('anterior',cast(:anterior as text),'nuevo',cast(:nuevo as text),'motivo',cast(:motivo as text)))
            """).setParameter("actor",actor).setParameter("id",id.toString()).setParameter("anterior",cambio.estadoEsperado().name())
                .setParameter("nuevo",cambio.nuevoEstado().name()).setParameter("motivo",cambio.motivo()).executeUpdate();
        return new UsuarioActualizado(id,cambio.nuevoEstado());
    }
}

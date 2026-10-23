package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.DashboardRepositoryPort;

@Repository
public class JpaDashboardAdapter implements DashboardRepositoryPort {
    @PersistenceContext EntityManager em;

    @Override @Transactional(readOnly=true)
    public ResumenDashboard resumir(ConsultaDashboard filtro) {
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        Map<String,Object> parametros=new LinkedHashMap<>();
        if(filtro.distritoId()!=null){where.append(" AND r.distrito_id=:distrito");parametros.put("distrito",filtro.distritoId());}
        if(filtro.desde()!=null){where.append(" AND r.fecha_creacion>=:desde");parametros.put("desde",filtro.desdeInclusivo());}
        if(filtro.hasta()!=null){where.append(" AND r.fecha_creacion<:hasta");parametros.put("hasta",filtro.hastaExclusivo());}
        // One statement gives every distribution the same MVCC snapshot. Catalog joins are many-to-one;
        // no photos/history joins can multiply a report. Only aggregate rows leave PostgreSQL.
        var query=em.createNativeQuery("""
            SELECT GROUPING(r.estado,r.severidad_declarada,r.tipo_barrera_id,r.distrito_id),
                   r.estado,r.severidad_declarada,r.tipo_barrera_id,r.distrito_id,count(*),min(t.nombre),min(d.nombre)
            FROM accesible.reportes r
            JOIN accesible.tipos_barrera t ON t.id=r.tipo_barrera_id
            JOIN accesible.distritos d ON d.id=r.distrito_id
            """+where+"""
             GROUP BY GROUPING SETS ((),(r.estado),(r.severidad_declarada),(r.tipo_barrera_id),(r.distrito_id))
            """);
        parametros.forEach(query::setParameter);
        var estados=new EnumMap<EstadoReporte,Long>(EstadoReporte.class);
        for(var estado:EstadoReporte.values())estados.put(estado,0L);
        var severidades=new EnumMap<Severidad,Long>(Severidad.class);
        for(var severidad:Severidad.values())severidades.put(severidad,0L);
        List<ResumenDashboard.DistribucionCatalogo> tipos=new ArrayList<>(),distritos=new ArrayList<>();
        long total=0;
        for(Object fila:query.getResultList()){
            var a=(Object[])fila;long cantidad=((Number)a[5]).longValue();
            // GROUPING bitmask: 15=total, 7=state, 11=severity, 13=type, 14=district.
            switch(((Number)a[0]).intValue()){
                case 15 -> total=cantidad;
                case 7 -> estados.put(EstadoReporte.valueOf(a[1].toString()),cantidad);
                case 11 -> severidades.put(Severidad.valueOf(a[2].toString()),cantidad);
                case 13 -> tipos.add(new ResumenDashboard.DistribucionCatalogo(((Number)a[3]).longValue(),(String)a[6],cantidad));
                case 14 -> distritos.add(new ResumenDashboard.DistribucionCatalogo(((Number)a[4]).longValue(),(String)a[7],cantidad));
                default -> throw new IllegalStateException("Unexpected dashboard grouping");
            }
        }
        tipos.sort(Comparator.comparingLong(ResumenDashboard.DistribucionCatalogo::id));
        distritos.sort(Comparator.comparingLong(ResumenDashboard.DistribucionCatalogo::id));
        long atendidos=estados.get(EstadoReporte.ATENDIDO),rechazados=estados.get(EstadoReporte.RECHAZADO);
        return new ResumenDashboard(filtro,total,total-atendidos-rechazados,atendidos,rechazados,estados,severidades,tipos,distritos);
    }
}

package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Read mapping only; transitions are not exposed by this feature. */
@Entity
@Table(name="historial_estados",schema="accesible")
public class HistorialEstadoEntity {
    @Id Long id;
    @Column(name="reporte_id",nullable=false) long reporteId;
    @Column(name="estado_anterior",length=20) String estadoAnterior;
    @Column(name="estado_nuevo",nullable=false,length=20) String estadoNuevo;
    @Column(length=500) String motivo;
    @Column(name="fecha_cambio",nullable=false) Instant fechaCambio;
    protected HistorialEstadoEntity() {}
}

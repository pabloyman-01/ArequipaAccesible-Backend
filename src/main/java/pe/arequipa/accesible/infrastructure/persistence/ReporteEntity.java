package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="reportes",schema="accesible")
public class ReporteEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,length=15) String codigo;
    @Column(name="ciudadano_id",nullable=false) UUID ciudadanoId;
    @Column(name="tipo_barrera_id",nullable=false) long tipoBarreraId;
    @Column(name="distrito_id",nullable=false) long distritoId;
    @Column(name="severidad_declarada",nullable=false,length=10) String severidad;
    @Column(nullable=false,length=3000) String descripcion;
    @Column(name="direccion_referencia",nullable=false,length=500) String direccion;
    @Column(nullable=false,precision=10,scale=7) BigDecimal latitud;
    @Column(nullable=false,precision=10,scale=7) BigDecimal longitud;
    @Column(nullable=false,length=20) String estado;
    @Column(nullable=false,length=20) String canal;
    @Column(name="fecha_creacion",nullable=false) Instant creado;
    @Column(name="fecha_actualizacion",nullable=false) Instant actualizado;
    protected ReporteEntity() {}
}

package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="reporte_fotos",schema="accesible")
public class FotoEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="reporte_id",nullable=false) long reporteId;
    @Column(name="object_key",nullable=false,length=200) String objectKey;
    @Column(name="mime_type",nullable=false,length=20) String mime;
    @Column(name="tamano_bytes",nullable=false) long tamano;
    @Column(name="clase_foto",nullable=false,length=20) String clase;
    @Column(name="creado_en",nullable=false) Instant creado;
    protected FotoEntity() {}
}

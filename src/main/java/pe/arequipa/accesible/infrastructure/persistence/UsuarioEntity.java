package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuarios", schema = "accesible")
public class UsuarioEntity {
    @Id UUID id;
    @Column(nullable = false, length = 100) String nombres;
    @Column(nullable = false, length = 100) String apellidos;
    @Column(name = "tipo_documento", nullable = false, length = 20) String tipoDocumento;
    @Column(nullable = false, length = 20) String documento;
    @Column(nullable = false, length = 254) String email;
    @Column(length = 20) String telefono;
    @Column(name = "distrito_id") Long distritoId;
    @Column(name = "estado_cuenta", nullable = false, length = 20) String estadoCuenta;
    @Column(name = "estado_verificacion", nullable = false, length = 20) String estadoVerificacion;
    @Column(name = "creado_en", nullable = false) Instant creadoEn;
    protected UsuarioEntity() {}
}

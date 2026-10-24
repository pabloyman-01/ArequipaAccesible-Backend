package pe.arequipa.accesible.domain.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UsuarioPerfil(UUID id, String nombres, String apellidos, String tipoDocumento,
        String documento, String email, String telefono, Long distritoId, String estadoCuenta,
        String estadoVerificacion, Instant creadoEn, Set<Rol> roles) {
    public UsuarioPerfil { roles = Set.copyOf(roles); }
    public boolean activo() { return "ACTIVA".equals(estadoCuenta); }
}

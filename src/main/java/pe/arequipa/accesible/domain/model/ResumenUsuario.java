package pe.arequipa.accesible.domain.model;

import java.time.Instant;
import java.util.*;

public record ResumenUsuario(UUID id, String nombres, String apellidos, String email, String tipoDocumento,
        String documentoEnmascarado, String estadoCuenta, String estadoVerificacion, Long distritoId, Set<Rol> roles, Instant creadoEn) {
    public ResumenUsuario { roles=Set.copyOf(roles); }
}

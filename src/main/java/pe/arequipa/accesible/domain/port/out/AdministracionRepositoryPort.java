package pe.arequipa.accesible.domain.port.out;

import java.time.Instant;
import java.util.*;
import pe.arequipa.accesible.domain.model.*;

public interface AdministracionRepositoryPort {
    record ReporteActualizado(long id, String codigo, EstadoReporte estado, Instant fechaActualizacion) {}
    record UsuarioActualizado(UUID id, EstadoCuenta estadoCuenta) {}
    ReporteActualizado cambiarEstadoReporte(long id, TransicionReporte cambio, UUID actor);
    List<ResumenUsuario> listarUsuarios(int limite, UUID cursor, EstadoCuenta estado, Rol rol, Long distritoId);
    UsuarioActualizado cambiarEstadoUsuario(UUID id, CambioEstadoCuenta cambio, UUID actor);
}

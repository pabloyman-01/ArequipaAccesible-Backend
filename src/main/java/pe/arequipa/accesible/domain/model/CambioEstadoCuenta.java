package pe.arequipa.accesible.domain.model;

import static pe.arequipa.accesible.domain.model.AdministracionException.Motivo.*;

public record CambioEstadoCuenta(EstadoCuenta estadoEsperado, EstadoCuenta nuevoEstado, String motivo) {
    public CambioEstadoCuenta {
        if (estadoEsperado==null || nuevoEstado==null) throw new AdministracionException(ESTADO_CONFLICTO);
        if (motivo==null || motivo.isBlank() || motivo.strip().length()>500) throw new AdministracionException(MOTIVO_INVALIDO);
        motivo=motivo.strip();
    }
}

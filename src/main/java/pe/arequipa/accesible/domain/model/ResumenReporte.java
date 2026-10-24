package pe.arequipa.accesible.domain.model;

import java.time.Instant;

public record ResumenReporte(long id, String codigo, long tipoBarreraId, long distritoId,
        String severidadDeclarada, String descripcion, String direccionReferencia, String estado, Instant fechaCreacion) {}

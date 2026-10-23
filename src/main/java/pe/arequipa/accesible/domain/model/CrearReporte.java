package pe.arequipa.accesible.domain.model;

import java.math.BigDecimal;
import pe.arequipa.accesible.domain.model.Severidad;

public record CrearReporte(Long tipoBarreraId, Severidad severidadDeclarada, String descripcion,
        Long distritoId, String direccionReferencia, BigDecimal latitud, BigDecimal longitud) {}

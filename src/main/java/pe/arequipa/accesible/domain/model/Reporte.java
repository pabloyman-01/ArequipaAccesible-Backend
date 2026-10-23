package pe.arequipa.accesible.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record Reporte(long id, String codigo, UUID ciudadanoId, long tipoBarreraId, long distritoId,
        Severidad severidadDeclarada, String descripcion, String direccionReferencia,
        BigDecimal latitud, BigDecimal longitud, String estado, Instant fechaCreacion, List<Foto> fotos) {
    public Reporte { fotos = List.copyOf(fotos); }
    public record Foto(long id, String objectKey, String mimeType, long tamanoBytes) {}
}

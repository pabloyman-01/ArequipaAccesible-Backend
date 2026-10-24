package pe.arequipa.accesible.domain.model;

import java.math.BigDecimal;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.*;

/** Configured bounding box, never claimed to be an official administrative polygon. */
public record AreaPermitida(BigDecimal latMin, BigDecimal latMax, BigDecimal lonMin, BigDecimal lonMax) {
    public boolean configurada() { return latMin != null && latMax != null && lonMin != null && lonMax != null; }
    public void validar(BigDecimal lat, BigDecimal lon) {
        if (!configurada()) throw new ReporteException(NO_CONFIGURADO);
        if (latMin.compareTo(latMax) >= 0 || lonMin.compareTo(lonMax) >= 0
                || latMin.compareTo(BigDecimal.valueOf(-90)) < 0 || latMax.compareTo(BigDecimal.valueOf(90)) > 0
                || lonMin.compareTo(BigDecimal.valueOf(-180)) < 0 || lonMax.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new ReporteException(NO_CONFIGURADO);
        }
        if (lat == null || lon == null || lat.compareTo(latMin) < 0 || lat.compareTo(latMax) > 0
                || lon.compareTo(lonMin) < 0 || lon.compareTo(lonMax) > 0) throw new ReporteException(INVALIDO);
    }
}

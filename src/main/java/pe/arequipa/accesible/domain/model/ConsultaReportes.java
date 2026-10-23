package pe.arequipa.accesible.domain.model;

import java.time.*;
import java.util.Locale;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.CONSULTA_INVALIDA;

public record ConsultaReportes(int limite, EstadoReporte estado, Long tipoBarreraId, Long distritoId,
        Severidad severidadDeclarada, LocalDate desde, LocalDate hasta, String codigo, Cursor cursor) {
    public ConsultaReportes {
        if (limite<1 || limite>100 || (tipoBarreraId!=null && tipoBarreraId<=0)
                || (distritoId!=null && distritoId<=0) || !fechaValida(desde) || !fechaValida(hasta)
                || (desde!=null && hasta!=null && desde.isAfter(hasta))) throw new ReporteException(CONSULTA_INVALIDA);
        if (codigo!=null) {
            codigo=codigo.strip().toUpperCase(Locale.ROOT);
            if (!codigo.matches("ARQ-[0-9]{4}-[0-9]{6}")) throw new ReporteException(CONSULTA_INVALIDA);
        }
    }
    private static boolean fechaValida(LocalDate fecha) { return fecha==null || (fecha.getYear()>=2000 && fecha.getYear()<=9999); }
    public Instant desdeInclusivo() { return desde==null?null:desde.atStartOfDay(ZoneId.of("America/Lima")).toInstant(); }
    public Instant hastaExclusivo() { return hasta==null?null:hasta.plusDays(1).atStartOfDay(ZoneId.of("America/Lima")).toInstant(); }
    public record Cursor(Instant fecha, long id) {
        public Cursor {
            if (fecha==null || id<=0 || fecha.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                    || !fecha.isBefore(Instant.parse("+10000-01-02T00:00:00Z"))) throw new ReporteException(CONSULTA_INVALIDA);
        }
    }
}

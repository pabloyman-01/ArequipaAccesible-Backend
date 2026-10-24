package pe.arequipa.accesible.domain.model;

import java.time.Instant;
import java.time.LocalDate;

public record ConsultaDashboard(LocalDate desde, LocalDate hasta, Long distritoId) {
    public ConsultaDashboard {
        new ConsultaReportes(1,null,null,distritoId,null,desde,hasta,null,null);
    }
    private ConsultaReportes filtro() { return new ConsultaReportes(1,null,null,distritoId,null,desde,hasta,null,null); }
    public Instant desdeInclusivo() { return filtro().desdeInclusivo(); }
    public Instant hastaExclusivo() { return filtro().hastaExclusivo(); }
}

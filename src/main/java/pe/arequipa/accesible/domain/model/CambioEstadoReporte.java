package pe.arequipa.accesible.domain.model;

import java.time.Instant;

public record CambioEstadoReporte(long id, String estadoAnterior, String estadoNuevo, Instant fechaCambio, String motivo) {}

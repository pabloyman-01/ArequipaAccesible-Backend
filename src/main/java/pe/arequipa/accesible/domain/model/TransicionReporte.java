package pe.arequipa.accesible.domain.model;

import static pe.arequipa.accesible.domain.model.AdministracionException.Motivo.*;

public record TransicionReporte(EstadoReporte estadoEsperado, EstadoReporte nuevoEstado, String motivo) {
    public TransicionReporte {
        if (estadoEsperado==null || nuevoEstado==null) throw new AdministracionException(TRANSICION_INVALIDA);
        motivo=motivo==null?null:motivo.strip();
        if (motivo!=null && motivo.length()>500 || nuevoEstado==EstadoReporte.RECHAZADO && (motivo==null || motivo.isBlank()))
            throw new AdministracionException(MOTIVO_INVALIDO);
        if (motivo!=null && motivo.isEmpty()) motivo=null;
    }
    public void validar(EstadoReporte actual) {
        if (actual!=estadoEsperado) throw new AdministracionException(ESTADO_CONFLICTO);
        boolean permitido=switch(actual) {
            case RECIBIDO -> nuevoEstado==EstadoReporte.EN_EVALUACION;
            case EN_EVALUACION -> nuevoEstado==EstadoReporte.EN_PROCESO || nuevoEstado==EstadoReporte.RECHAZADO;
            case ASIGNADO -> nuevoEstado==EstadoReporte.EN_PROCESO;
            case EN_PROCESO -> nuevoEstado==EstadoReporte.ATENDIDO;
            case ATENDIDO, RECHAZADO -> false;
        };
        if (!permitido) throw new AdministracionException(TRANSICION_INVALIDA);
    }
}

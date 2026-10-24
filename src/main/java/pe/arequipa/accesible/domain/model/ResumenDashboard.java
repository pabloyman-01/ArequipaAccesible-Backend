package pe.arequipa.accesible.domain.model;

import java.util.*;

public record ResumenDashboard(ConsultaDashboard filtros, long total, long pendientes, long atendidos, long rechazados,
        Map<EstadoReporte,Long> porEstado, Map<Severidad,Long> porSeveridadDeclarada,
        List<DistribucionCatalogo> porTipoBarrera, List<DistribucionCatalogo> porDistrito) {
    public record DistribucionCatalogo(long id, String nombre, long cantidad) {}
    public ResumenDashboard {
        porEstado=Collections.unmodifiableMap(new EnumMap<>(porEstado));
        porSeveridadDeclarada=Collections.unmodifiableMap(new EnumMap<>(porSeveridadDeclarada));
        porTipoBarrera=List.copyOf(porTipoBarrera);
        porDistrito=List.copyOf(porDistrito);
    }
}

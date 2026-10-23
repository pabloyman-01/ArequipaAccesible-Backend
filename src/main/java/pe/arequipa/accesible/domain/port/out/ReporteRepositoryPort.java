package pe.arequipa.accesible.domain.port.out;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import pe.arequipa.accesible.domain.model.*;

public interface ReporteRepositoryPort {
    void validarCatalogos(CrearReporte datos);
    UUID reservarCarga(UUID actor, String objectKey, int limitePorMinuto);
    Reporte crear(CrearReporte datos, UUID actor, UUID carga, ImagenValidada imagen, Instant fecha);
    Optional<Reporte> buscar(long id, UUID actor, boolean administrador);
    Optional<List<CambioEstadoReporte>> historial(long id, UUID actor, boolean administrador);
    List<ResumenReporte> listarPropios(UUID actor, ConsultaReportes consulta);
    void limpiarCarga(UUID carga, boolean inmediata, Consumer<String> eliminar);
    List<UUID> cargasVencidas();
    record Catalogo(long id, String codigo, String nombre, String descripcion) {}
    List<Catalogo> distritos();
    List<Catalogo> tiposBarrera();
}

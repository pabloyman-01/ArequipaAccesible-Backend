package pe.arequipa.accesible.infrastructure.storage;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.arequipa.accesible.domain.port.out.*;

@Component
public class LimpiezaArchivos {
    private static final Logger LOG=LoggerFactory.getLogger(LimpiezaArchivos.class);
    private final ReporteRepositoryPort reportes;
    private final ArchivoStoragePort storage;
    public LimpiezaArchivos(ReporteRepositoryPort reportes, ArchivoStoragePort storage) { this.reportes=reportes; this.storage=storage; }
    @Scheduled(fixedDelayString="${app.reportes.limpieza-ms:60000}",initialDelayString="${app.reportes.limpieza-ms:60000}")
    public void ejecutar() {
        try { for (var id:reportes.cargasVencidas()) reportes.limpiarCarga(id,false,storage::eliminar); }
        catch (RuntimeException e) { LOG.warn("storage_cleanup_deferred type={}",e.getClass().getSimpleName()); }
    }
}

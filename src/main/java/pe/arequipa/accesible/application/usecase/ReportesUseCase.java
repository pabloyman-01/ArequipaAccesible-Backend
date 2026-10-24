package pe.arequipa.accesible.application.usecase;

import java.time.Clock;
import java.util.UUID;
import java.util.List;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.*;

public class ReportesUseCase {
    private final UsuarioRepositoryPort usuarios;
    private final ReporteRepositoryPort reportes;
    private final ArchivoStoragePort storage;
    private final ImagenValidatorPort imagenes;
    private final AreaPermitida area;
    private final Clock clock;
    private final boolean habilitado;
    private final int limite;
    public ReportesUseCase(UsuarioRepositoryPort usuarios, ReporteRepositoryPort reportes, ArchivoStoragePort storage,
            ImagenValidatorPort imagenes, AreaPermitida area, Clock clock, boolean habilitado, int limite) {
        this.usuarios=usuarios; this.reportes=reportes; this.storage=storage; this.imagenes=imagenes;
        this.area=area; this.clock=clock; this.habilitado=habilitado; this.limite=limite;
    }
    private UsuarioPerfil actor(UUID id) {
        var actor = usuarios.buscar(id).orElseThrow(() -> new ReporteException(SIN_PERMISO));
        if (!actor.activo()) throw new ReporteException(SIN_PERMISO);
        return actor;
    }
    public Reporte crear(CrearReporte datos, byte[] contenido, String mime, UUID actorId) {
        var actor = actor(actorId);
        if (!actor.roles().contains(Rol.CIUDADANO)) throw new ReporteException(SIN_PERMISO);
        if (!habilitado) throw new ReporteException(NO_CONFIGURADO);
        if (datos == null || datos.tipoBarreraId() == null || datos.tipoBarreraId() <= 0
                || datos.distritoId() == null || datos.distritoId() <= 0 || datos.severidadDeclarada() == null
                || datos.descripcion() == null || datos.descripcion().isBlank() || datos.descripcion().length() > 3000
                || datos.direccionReferencia() == null || datos.direccionReferencia().isBlank()
                || datos.direccionReferencia().length() > 500) throw new ReporteException(INVALIDO);
        area.validar(datos.latitud(),datos.longitud());
        reportes.validarCatalogos(datos);
        var imagen = imagenes.validar(contenido,mime);
        String key = "evidencias/"+UUID.randomUUID()+"."+imagen.extension();
        UUID carga = reportes.reservarCarga(actorId,key,limite);
        // A failed/ambiguous upload keeps the durable intent for delayed reconciliation.
        storage.subir(key,imagen);
        try { return reportes.crear(datos,actorId,carga,imagen,clock.instant()); }
        catch (RuntimeException e) {
            // If COMMIT succeeded but the connection failed, the intent is already gone:
            // limpiarCarga then does nothing, preserving the committed report's evidence.
            try { reportes.limpiarCarga(carga,true,storage::eliminar); } catch (RuntimeException ignored) { /* durable intent remains */ }
            throw e;
        }
    }
    public Reporte consultar(long id, UUID actorId) {
        var actor = actor(actorId);
        return reportes.buscar(id,actorId,actor.roles().contains(Rol.ADMINISTRADOR))
                .orElseThrow(() -> new ReporteException(NO_ENCONTRADO));
    }
    public List<CambioEstadoReporte> historial(long id, UUID actorId) {
        var actor=actor(actorId);
        return List.copyOf(reportes.historial(id,actorId,actor.roles().contains(Rol.ADMINISTRADOR))
                .orElseThrow(() -> new ReporteException(NO_ENCONTRADO)));
    }
    public record Pagina(List<ResumenReporte> elementos, int limite, boolean hayMas, ConsultaReportes.Cursor siguiente) {
        public Pagina { elementos=List.copyOf(elementos); }
    }
    public Pagina listarPropios(ConsultaReportes consulta, UUID actorId) {
        var actor=actor(actorId);
        if (!actor.roles().contains(Rol.CIUDADANO)) throw new ReporteException(SIN_PERMISO);
        if (consulta==null) throw new ReporteException(CONSULTA_INVALIDA);
        return paginar(reportes.listarPropios(actorId,consulta),consulta);
    }
    public static Pagina paginar(List<ResumenReporte> filas, ConsultaReportes consulta) {
        boolean hayMas=filas.size()>consulta.limite();
        var pagina=List.copyOf(filas.subList(0,Math.min(filas.size(),consulta.limite())));
        var ultimo=pagina.isEmpty()?null:pagina.getLast();
        return new Pagina(pagina,consulta.limite(),hayMas,hayMas?new ConsultaReportes.Cursor(ultimo.fechaCreacion(),ultimo.id()):null);
    }
    public record FotoContenido(String mimeType, byte[] contenido) {}
    public FotoContenido foto(long reporteId, long fotoId, UUID actorId) {
        var foto = consultar(reporteId,actorId).fotos().stream().filter(f -> f.id()==fotoId)
                .findFirst().orElseThrow(() -> new ReporteException(NO_ENCONTRADO));
        return new FotoContenido(foto.mimeType(),storage.descargar(foto.objectKey()));
    }
}

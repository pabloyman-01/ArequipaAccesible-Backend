package pe.arequipa.accesible.application.usecase;

import java.util.*;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;
import static pe.arequipa.accesible.domain.model.AdministracionException.Motivo.*;

public class AdministracionUseCase {
    private final UsuarioRepositoryPort usuarios;
    private final ReporteRepositoryPort reportes;
    private final AdministracionRepositoryPort administracion;
    public AdministracionUseCase(UsuarioRepositoryPort usuarios, ReporteRepositoryPort reportes, AdministracionRepositoryPort administracion) {
        this.usuarios=usuarios;this.reportes=reportes;this.administracion=administracion;
    }
    private void autorizar(UUID actor) {
        var usuario=usuarios.buscar(actor).orElseThrow(()->new AdministracionException(SIN_PERMISO));
        if (!usuario.activo() || !usuario.roles().contains(Rol.ADMINISTRADOR)) throw new AdministracionException(SIN_PERMISO);
    }
    public ReportesUseCase.Pagina listarReportes(ConsultaReportes consulta, UUID ciudadano, UUID actor) {
        autorizar(actor);
        return ReportesUseCase.paginar(reportes.listarAdministracion(ciudadano,consulta),consulta);
    }
    public AdministracionRepositoryPort.ReporteActualizado cambiarReporte(long id, TransicionReporte cambio, UUID actor) {
        autorizar(actor);return administracion.cambiarEstadoReporte(id,cambio,actor);
    }
    public record PaginaUsuarios(List<ResumenUsuario> elementos, int limite, boolean hayMas, UUID siguienteCursor) {
        public PaginaUsuarios { elementos=List.copyOf(elementos); }
    }
    public PaginaUsuarios listarUsuarios(int limite, UUID cursor, EstadoCuenta estado, Rol rol, Long distrito, UUID actor) {
        autorizar(actor);
        if (limite<1 || limite>100 || distrito!=null && distrito<=0) throw new AdministracionException(CONSULTA_INVALIDA);
        var filas=administracion.listarUsuarios(limite,cursor,estado,rol,distrito);
        boolean hayMas=filas.size()>limite;var pagina=filas.subList(0,Math.min(limite,filas.size()));
        return new PaginaUsuarios(pagina,limite,hayMas,hayMas?pagina.getLast().id():null);
    }
    public AdministracionRepositoryPort.UsuarioActualizado cambiarUsuario(UUID id, CambioEstadoCuenta cambio, UUID actor) {
        autorizar(actor);return administracion.cambiarEstadoUsuario(id,cambio,actor);
    }
}

package pe.arequipa.accesible.application.usecase;

import java.util.UUID;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;

public class DashboardUseCase {
    private final UsuarioRepositoryPort usuarios;
    private final DashboardRepositoryPort dashboard;
    public DashboardUseCase(UsuarioRepositoryPort usuarios, DashboardRepositoryPort dashboard) {
        this.usuarios=usuarios;this.dashboard=dashboard;
    }
    public ResumenDashboard resumir(ConsultaDashboard consulta, UUID actor) {
        var usuario=usuarios.buscar(actor).orElseThrow(()->new AdministracionException(AdministracionException.Motivo.SIN_PERMISO));
        if(!usuario.activo() || !usuario.roles().contains(Rol.ADMINISTRADOR))
            throw new AdministracionException(AdministracionException.Motivo.SIN_PERMISO);
        return dashboard.resumir(consulta);
    }
}

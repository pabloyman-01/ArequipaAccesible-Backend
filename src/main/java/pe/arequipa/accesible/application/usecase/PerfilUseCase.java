package pe.arequipa.accesible.application.usecase;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import pe.arequipa.accesible.application.command.*;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.UsuarioRepositoryPort;
import static pe.arequipa.accesible.domain.model.PerfilException.Motivo.*;

public class PerfilUseCase {
    private final UsuarioRepositoryPort usuarios;
    private final Clock clock;
    public PerfilUseCase(UsuarioRepositoryPort usuarios, Clock clock) { this.usuarios = usuarios; this.clock = clock; }
    public UsuarioPerfil consultar(UUID id) {
        var perfil = usuarios.buscar(id).orElseThrow(() -> new PerfilException(NO_ENCONTRADO));
        if (!perfil.activo()) throw new PerfilException(SUSPENDIDO);
        return perfil;
    }
    public UsuarioPerfil registrar(UUID id, String emailVerificado, DatosPerfil datos) {
        var existente = usuarios.buscar(id);
        if (existente.isPresent()) return consultar(id);
        validarDistrito(datos.distritoId());
        var perfil = new UsuarioPerfil(id, datos.nombres(), datos.apellidos(), datos.tipoDocumento(),
                datos.documento(), emailVerificado.toLowerCase(java.util.Locale.ROOT), datos.telefono(),
                datos.distritoId(), "ACTIVA", "NO_VERIFICADO", clock.instant(), Set.of(Rol.CIUDADANO));
        var resultado = usuarios.registrarSiAusente(perfil);
        if (!resultado.activo()) throw new PerfilException(SUSPENDIDO);
        return resultado;
    }
    public UsuarioPerfil actualizar(UUID id, ActualizarPerfil datos) {
        consultar(id);
        validarDistrito(datos.distritoId());
        return usuarios.actualizarContacto(id, datos.nombres(), datos.apellidos(), datos.telefono(), datos.distritoId());
    }
    private void validarDistrito(Long id) {
        if (id != null && !usuarios.distritoActivo(id)) throw new PerfilException(DISTRITO_INVALIDO);
    }
}

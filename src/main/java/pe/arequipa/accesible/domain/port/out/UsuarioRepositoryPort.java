package pe.arequipa.accesible.domain.port.out;

import java.util.Optional;
import java.util.UUID;
import pe.arequipa.accesible.domain.model.UsuarioPerfil;

public interface UsuarioRepositoryPort {
    Optional<UsuarioPerfil> buscar(UUID id);
    UsuarioPerfil registrarSiAusente(UsuarioPerfil perfil);
    UsuarioPerfil actualizarContacto(UUID id, String nombres, String apellidos, String telefono, Long distritoId);
    boolean distritoActivo(Long id);
}

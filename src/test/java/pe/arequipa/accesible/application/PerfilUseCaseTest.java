package pe.arequipa.accesible.application;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.arequipa.accesible.application.command.*;
import pe.arequipa.accesible.application.usecase.PerfilUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.UsuarioRepositoryPort;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfilUseCaseTest {
    @Mock UsuarioRepositoryPort repo;
    final UUID id = UUID.randomUUID();
    PerfilUseCase service;
    @BeforeEach void setup() { service = new PerfilUseCase(repo,Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneOffset.UTC)); }
    UsuarioPerfil perfil(String estado) { return new UsuarioPerfil(id,"Ana","Perez","DNI","12345678","test@example.test",null,null,estado,"NO_VERIFICADO",Instant.now(),Set.of(Rol.CIUDADANO)); }
    DatosPerfil datos(Long distrito) { return new DatosPerfil("Ana","Perez","DNI","12345678",null,distrito); }
    @Test void altaSoloCiudadano() {
        when(repo.buscar(id)).thenReturn(Optional.empty());
        when(repo.registrarSiAusente(any())).thenAnswer(i -> i.getArgument(0));
        var p = service.registrar(id,"ANA@EXAMPLE.TEST",datos(null));
        assertThat(p.id()).isEqualTo(id);
        assertThat(p.roles()).containsExactly(Rol.CIUDADANO);
        assertThat(p.email()).isEqualTo("ana@example.test");
        assertThat(p.estadoVerificacion()).isEqualTo("NO_VERIFICADO");
    }
    @Test void reintentoNoSobreescribePerfil() {
        when(repo.buscar(id)).thenReturn(Optional.of(perfil("ACTIVA")));
        assertThat(service.registrar(id,"otra@example.test",datos(null)).email()).isEqualTo("test@example.test");
        verify(repo,never()).registrarSiAusente(any());
    }
    @Test void noPermiteSuspensionEludidaPorAlta() {
        when(repo.buscar(id)).thenReturn(Optional.of(perfil("SUSPENDIDA")));
        assertThatThrownBy(() -> service.registrar(id,"test@example.test",datos(null))).isInstanceOf(PerfilException.class);
        verify(repo,never()).registrarSiAusente(any());
    }
    @Test void distritoInactivoNoPersiste() {
        when(repo.buscar(id)).thenReturn(Optional.empty());
        when(repo.distritoActivo(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.registrar(id,"test@example.test",datos(99L))).isInstanceOf(PerfilException.class);
        verify(repo,never()).registrarSiAusente(any());
    }
    @Test void actualizacionSuspendidaNoPersiste() {
        when(repo.buscar(id)).thenReturn(Optional.of(perfil("SUSPENDIDA")));
        assertThatThrownBy(() -> service.actualizar(id,new ActualizarPerfil("Otra",null,null,null))).isInstanceOf(PerfilException.class);
        verify(repo,never()).actualizarContacto(any(),any(),any(),any(),any());
    }
    @Test void perfilAusente() {
        when(repo.buscar(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consultar(id)).isInstanceOf(PerfilException.class);
    }
}

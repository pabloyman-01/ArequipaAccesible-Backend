package pe.arequipa.accesible.application;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import pe.arequipa.accesible.application.usecase.DashboardUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardUseCaseTest {
    @Test void directCallsRequireActiveAdministratorBeforeReadingAggregates() {
        var usuarios=mock(UsuarioRepositoryPort.class);var repository=mock(DashboardRepositoryPort.class);
        var service=new DashboardUseCase(usuarios,repository);UUID id=UUID.randomUUID();
        var citizen=new UsuarioPerfil(id,"Ana","Prueba","DNI","00000000","test@example.test",null,null,"ACTIVA","NO_VERIFICADO",Instant.now(),Set.of(Rol.CIUDADANO));
        var suspended=new UsuarioPerfil(id,"Ana","Prueba","DNI","00000000","test@example.test",null,null,"SUSPENDIDA","NO_VERIFICADO",Instant.now(),Set.of(Rol.ADMINISTRADOR));
        for(var perfil:List.of(Optional.<UsuarioPerfil>empty(),Optional.of(citizen),Optional.of(suspended))) {
            when(usuarios.buscar(id)).thenReturn(perfil);
            assertThatThrownBy(()->service.resumir(new ConsultaDashboard(null,null,null),id))
                    .isInstanceOfSatisfying(AdministracionException.class,e->assertThat(e.motivo()).isEqualTo(AdministracionException.Motivo.SIN_PERMISO));
        }
        verifyNoInteractions(repository);
    }
    @Test void dateFiltersUseInclusiveLimaDaysIncludingMaximumSupportedYear() {
        var filtro=new ConsultaDashboard(LocalDate.parse("2026-10-23"),LocalDate.parse("2026-10-23"),1L);
        assertThat(filtro.desdeInclusivo()).isEqualTo(Instant.parse("2026-10-23T05:00:00Z"));
        assertThat(filtro.hastaExclusivo()).isEqualTo(Instant.parse("2026-10-24T05:00:00Z"));
        assertThat(new ConsultaDashboard(null,LocalDate.parse("9999-12-31"),null).hastaExclusivo())
                .isEqualTo(Instant.parse("+10000-01-01T05:00:00Z"));
    }
    @Test void invalidDatesAndDistrictsAreRejected() {
        for(Long distrito:List.of(0L,-1L))assertThatThrownBy(()->new ConsultaDashboard(null,null,distrito)).isInstanceOf(ReporteException.class);
        assertThatThrownBy(()->new ConsultaDashboard(LocalDate.of(1999,12,31),null,null)).isInstanceOf(ReporteException.class);
        assertThatThrownBy(()->new ConsultaDashboard(null,LocalDate.of(10000,1,1),null)).isInstanceOf(ReporteException.class);
        assertThatThrownBy(()->new ConsultaDashboard(LocalDate.of(2026,10,24),LocalDate.of(2026,10,23),null)).isInstanceOf(ReporteException.class);
    }
}

package pe.arequipa.accesible.application;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pe.arequipa.accesible.application.usecase.AdministracionUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdministracionTest {
    @ParameterizedTest @EnumSource(EstadoReporte.class)
    void canonicalTransitionMatrix(EstadoReporte origen) {
        Set<String> valid=Set.of("RECIBIDO:EN_EVALUACION","EN_EVALUACION:EN_PROCESO","EN_EVALUACION:RECHAZADO","ASIGNADO:EN_PROCESO","EN_PROCESO:ATENDIDO");
        for(var nuevo:EstadoReporte.values()) {
            var cambio=new TransicionReporte(origen,nuevo,"Motivo técnico de prueba");
            if(valid.contains(origen+":"+nuevo))assertThatCode(()->cambio.validar(origen)).doesNotThrowAnyException();
            else assertThatThrownBy(()->cambio.validar(origen)).isInstanceOf(AdministracionException.class);
        }
    }
    @Test void rejectionRequiresReasonAndStaleStateIsConflict() {
        for(String motivo:Arrays.asList(null,"  ","x".repeat(501)))assertThatThrownBy(()->new TransicionReporte(EstadoReporte.EN_EVALUACION,EstadoReporte.RECHAZADO,motivo)).isInstanceOf(AdministracionException.class);
        assertThatThrownBy(()->new TransicionReporte(EstadoReporte.RECIBIDO,EstadoReporte.EN_EVALUACION,null).validar(EstadoReporte.EN_EVALUACION))
                .isInstanceOfSatisfying(AdministracionException.class,e->assertThat(e.motivo()).isEqualTo(AdministracionException.Motivo.ESTADO_CONFLICTO));
        assertThat(new TransicionReporte(EstadoReporte.EN_EVALUACION,EstadoReporte.RECHAZADO," Razón ").motivo()).isEqualTo("Razón");
    }
    @Test void allAdministrativeUseCasesRejectMissingCitizenOrSuspendedProfiles() {
        var usuarios=mock(UsuarioRepositoryPort.class);var reportes=mock(ReporteRepositoryPort.class);var repository=mock(AdministracionRepositoryPort.class);
        var service=new AdministracionUseCase(usuarios,reportes,repository);UUID id=UUID.randomUUID();
        var citizen=new UsuarioPerfil(id,"Ana","Prueba","DNI","00000000","test@example.test",null,null,"ACTIVA","NO_VERIFICADO",Instant.now(),Set.of(Rol.CIUDADANO));
        var suspended=new UsuarioPerfil(id,"Ana","Prueba","DNI","00000000","test@example.test",null,null,"SUSPENDIDA","NO_VERIFICADO",Instant.now(),Set.of(Rol.ADMINISTRADOR));
        for(var perfil:List.of(Optional.<UsuarioPerfil>empty(),Optional.of(citizen),Optional.of(suspended))) {
            when(usuarios.buscar(id)).thenReturn(perfil);
            assertThatThrownBy(()->service.listarReportes(new ConsultaReportes(20,null,null,null,null,null,null,null,null),null,id)).isInstanceOf(AdministracionException.class);
            assertThatThrownBy(()->service.cambiarReporte(1,new TransicionReporte(EstadoReporte.RECIBIDO,EstadoReporte.EN_EVALUACION,null),id)).isInstanceOf(AdministracionException.class);
            assertThatThrownBy(()->service.listarUsuarios(20,null,null,null,null,id)).isInstanceOf(AdministracionException.class);
            assertThatThrownBy(()->service.cambiarUsuario(UUID.randomUUID(),new CambioEstadoCuenta(EstadoCuenta.ACTIVA,EstadoCuenta.SUSPENDIDA,"Prueba"),id)).isInstanceOf(AdministracionException.class);
        }
        verifyNoInteractions(reportes,repository);
    }
}

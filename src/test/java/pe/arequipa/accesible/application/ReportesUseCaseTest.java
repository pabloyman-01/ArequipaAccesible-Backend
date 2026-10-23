package pe.arequipa.accesible.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.arequipa.accesible.application.usecase.ReportesUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class ReportesUseCaseTest {
    @Mock UsuarioRepositoryPort usuarios;
    @Mock ReporteRepositoryPort repo;
    @Mock ArchivoStoragePort storage;
    @Mock ImagenValidatorPort images;
    UUID id=UUID.randomUUID(), pending=UUID.randomUUID();
    ReportesUseCase service;
    CrearReporte input=new CrearReporte(1L,Severidad.ALTA,"Rampa de prueba",1L,"Dirección de prueba",new BigDecimal("-16.4"),new BigDecimal("-71.5"));
    @BeforeEach void setup() {
        service=new ReportesUseCase(usuarios,repo,storage,images,
                new AreaPermitida(new BigDecimal("-17"),new BigDecimal("-16"),new BigDecimal("-72"),new BigDecimal("-71")),Clock.systemUTC(),true,5);
    }
    void citizen() { when(usuarios.buscar(id)).thenReturn(Optional.of(new UsuarioPerfil(id,"Ana","Test","DNI","00000000","test@example.test",null,null,"ACTIVA","NO_VERIFICADO",Instant.now(),Set.of(Rol.CIUDADANO)))); }
    void uploadReady() {
        citizen(); when(images.validar(any(),any())).thenReturn(new ImagenValidada(new byte[]{1},"image/png","png"));
        when(repo.reservarCarga(eq(id),anyString(),eq(5))).thenReturn(pending);
    }
    @Test void listWorksWhenNewUploadsAreDisabled() {
        citizen();var query=new ConsultaReportes(20,null,null,null,null,null,null,null,null);
        var disabled=new ReportesUseCase(usuarios,repo,storage,images,new AreaPermitida(null,null,null,null),Clock.systemUTC(),false,5);
        when(repo.listarPropios(id,query)).thenReturn(List.of());
        assertThat(disabled.listarPropios(query,id).elementos()).isEmpty();
        verifyNoInteractions(storage,images);
    }
    @Test void listRechecksActiveCitizenInApplicationLayer() {
        when(usuarios.buscar(id)).thenReturn(Optional.of(new UsuarioPerfil(id,"Ana","Test","DNI","00000000","test@example.test",null,null,"SUSPENDIDA","NO_VERIFICADO",Instant.now(),Set.of(Rol.CIUDADANO))));
        var query=new ConsultaReportes(20,null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->service.listarPropios(query,id)).isInstanceOfSatisfying(ReporteException.class,
                e->assertThat(e.motivo()).isEqualTo(ReporteException.Motivo.SIN_PERMISO));
        verifyNoInteractions(repo,storage,images);
    }
    @Test void adminWithoutCitizenCannotListThroughApplicationLayer() {
        when(usuarios.buscar(id)).thenReturn(Optional.of(new UsuarioPerfil(id,"Ana","Test","DNI","00000000","test@example.test",null,null,"ACTIVA","NO_VERIFICADO",Instant.now(),Set.of(Rol.ADMINISTRADOR))));
        var query=new ConsultaReportes(20,null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->service.listarPropios(query,id)).isInstanceOfSatisfying(ReporteException.class,
                e->assertThat(e.motivo()).isEqualTo(ReporteException.Motivo.SIN_PERMISO));
        verifyNoInteractions(repo,storage,images);
    }
    @Test void invalidBusinessDataNeverUploads() {
        citizen(); var bad=new CrearReporte(1L,Severidad.ALTA," ",1L,"Test",input.latitud(),input.longitud());
        assertThatThrownBy(() -> service.crear(bad,new byte[]{1},"image/png",id)).isInstanceOf(ReporteException.class);
        verifyNoInteractions(storage,images,repo);
    }
    @Test void uploadHappensOnlyAfterDurableReservation() {
        uploadReady(); service.crear(input,new byte[]{1},"image/png",id);
        var order=inOrder(repo,storage);
        order.verify(repo).reservarCarga(eq(id),anyString(),eq(5)); order.verify(storage).subir(anyString(),any());
        order.verify(repo).crear(eq(input),eq(id),eq(pending),any(),any());
    }
    @Test void failedPersistenceRunsGuardedCompensation() {
        uploadReady(); when(repo.crear(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("test only"));
        assertThatThrownBy(() -> service.crear(input,new byte[]{1},"image/png",id)).isInstanceOf(IllegalStateException.class);
        verify(repo).limpiarCarga(eq(pending),eq(true),any());
        // Deletion is guarded by the persisted intent; it is never unconditional here.
        verify(storage,never()).eliminar(anyString());
    }
    @Test void ambiguousUploadDoesNotPrematurelyDelete() {
        uploadReady(); doThrow(new ReporteException(ReporteException.Motivo.STORAGE_NO_DISPONIBLE)).when(storage).subir(anyString(),any());
        assertThatThrownBy(() -> service.crear(input,new byte[]{1},"image/png",id)).isInstanceOf(ReporteException.class);
        verify(repo,never()).crear(any(),any(),any(),any(),any()); verify(repo,never()).limpiarCarga(any(),anyBoolean(),any());
    }
    @Test void missingConfigurationFailsClosed() {
        citizen();
        var disabled=new ReportesUseCase(usuarios,repo,storage,images,new AreaPermitida(null,null,null,null),Clock.systemUTC(),false,5);
        assertThatThrownBy(() -> disabled.crear(input,new byte[]{1},"image/png",id))
                .isInstanceOfSatisfying(ReporteException.class,e -> assertThat(e.motivo()).isEqualTo(ReporteException.Motivo.NO_CONFIGURADO));
        verifyNoInteractions(repo,storage,images);
    }
    @Test void missingProfileCannotCreate() {
        when(usuarios.buscar(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.crear(input,new byte[]{1},"image/png",id)).isInstanceOf(ReporteException.class);
        verifyNoInteractions(repo,storage,images);
    }
    @Test void missingOrForeignReportHasSameResponse() {
        citizen(); when(repo.buscar(99,id,false)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consultar(99,id)).isInstanceOfSatisfying(ReporteException.class,
                e -> assertThat(e.motivo()).isEqualTo(ReporteException.Motivo.NO_ENCONTRADO));
    }
    @Test void unconfiguredAndOutOfRangeAreaAreRejected() {
        assertThatThrownBy(() -> new AreaPermitida(null,null,null,null).validar(input.latitud(),input.longitud())).isInstanceOf(ReporteException.class);
        assertThatThrownBy(() -> new AreaPermitida(new BigDecimal("-17"),new BigDecimal("-16"),new BigDecimal("-72"),new BigDecimal("-71"))
                .validar(new BigDecimal("91"),input.longitud())).isInstanceOf(ReporteException.class);
    }
}

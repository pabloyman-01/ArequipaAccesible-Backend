package pe.arequipa.accesible.presentation.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import pe.arequipa.accesible.application.usecase.AdministracionUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.AdministracionRepositoryPort;

@RestController
@RequestMapping("/api/admin")
public class AdministracionController {
    private final AdministracionUseCase admin;
    public AdministracionController(AdministracionUseCase admin) { this.admin=admin; }
    public record CambioReporte(@NotNull EstadoReporte estadoEsperado, @NotNull EstadoReporte nuevoEstado, String motivo) {}
    public record CambioUsuario(@NotNull EstadoCuenta estadoEsperado, @NotNull EstadoCuenta nuevoEstado, String motivo) {}
    private static void parametros(MultiValueMap<String,String> params,Set<String> permitidos) {
        if(params.entrySet().stream().anyMatch(p->!permitidos.contains(p.getKey())||p.getValue().size()!=1||p.getValue().getFirst().isBlank()))
            throw new AdministracionException(AdministracionException.Motivo.CONSULTA_INVALIDA);
    }
    @GetMapping("/reportes")
    public ResponseEntity<ReportesController.Pagina> reportes(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="20") int limite,
            @RequestParam(required=false) EstadoReporte estado,
            @RequestParam(required=false) Severidad severidadDeclarada,
            @RequestParam(required=false) Long tipoBarreraId, @RequestParam(required=false) Long distritoId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required=false) String codigo, @RequestParam(required=false) String cursor,
            @RequestParam(required=false) UUID ciudadanoId, @RequestParam MultiValueMap<String,String> params) {
        parametros(params,Set.of("limite","estado","severidadDeclarada","tipoBarreraId","distritoId","desde","hasta","codigo","cursor","ciudadanoId"));
        var filtro=new ConsultaReportes(limite,estado,tipoBarreraId,distritoId,severidadDeclarada,desde,hasta,codigo,ReportesController.decodificar(cursor));
        var pagina=admin.listarReportes(filtro,ciudadanoId,UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReportesController.Pagina(
                pagina.elementos(),pagina.limite(),pagina.hayMas(),ReportesController.codificar(pagina.siguiente())));
    }
    @PatchMapping("/reportes/{id}/estado")
    public ResponseEntity<AdministracionRepositoryPort.ReporteActualizado> cambiarReporte(@PathVariable long id,
            @Valid @RequestBody CambioReporte cambio,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admin.cambiarReporte(id,
                new TransicionReporte(cambio.estadoEsperado(),cambio.nuevoEstado(),cambio.motivo()),UUID.fromString(jwt.getSubject())));
    }
    @GetMapping("/usuarios")
    public ResponseEntity<AdministracionUseCase.PaginaUsuarios> usuarios(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="20") int limite,@RequestParam(required=false) UUID cursor,
            @RequestParam(required=false) EstadoCuenta estadoCuenta,@RequestParam(required=false) Rol rol,
            @RequestParam(required=false) Long distritoId,@RequestParam MultiValueMap<String,String> params) {
        parametros(params,Set.of("limite","cursor","estadoCuenta","rol","distritoId"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admin.listarUsuarios(
                limite,cursor,estadoCuenta,rol,distritoId,UUID.fromString(jwt.getSubject())));
    }
    @PatchMapping("/usuarios/{id}/estado")
    public ResponseEntity<AdministracionRepositoryPort.UsuarioActualizado> cambiarUsuario(@PathVariable UUID id,
            @Valid @RequestBody CambioUsuario cambio,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admin.cambiarUsuario(id,
                new CambioEstadoCuenta(cambio.estadoEsperado(),cambio.nuevoEstado(),cambio.motivo()),UUID.fromString(jwt.getSubject())));
    }
}

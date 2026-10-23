package pe.arequipa.accesible.presentation.rest;

import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import pe.arequipa.accesible.application.usecase.DashboardUseCase;
import pe.arequipa.accesible.domain.model.*;

@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {
    private final DashboardUseCase dashboard;
    public DashboardController(DashboardUseCase dashboard) { this.dashboard=dashboard; }
    @GetMapping("/resumen")
    public ResponseEntity<ResumenDashboard> resumen(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required=false) Long distritoId,@RequestParam MultiValueMap<String,String> params) {
        if(params.entrySet().stream().anyMatch(p->!Set.of("desde","hasta","distritoId").contains(p.getKey())
                ||p.getValue().size()!=1||p.getValue().getFirst().isBlank()))
            throw new AdministracionException(AdministracionException.Motivo.CONSULTA_INVALIDA);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                dashboard.resumir(new ConsultaDashboard(desde,hasta,distritoId),UUID.fromString(jwt.getSubject())));
    }
}

package pe.arequipa.accesible.presentation.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import pe.arequipa.accesible.application.usecase.ReportesUseCase;
import pe.arequipa.accesible.domain.model.*;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.*;

@RestController
@RequestMapping("/api/reportes")
public class ReportesController {
    private final ReportesUseCase reportes;
    public ReportesController(ReportesUseCase reportes) { this.reportes=reportes; }
    public record Datos(@NotNull @Positive Long tipoBarreraId, @NotNull Severidad severidadDeclarada,
            @NotNull String descripcion, @NotNull @Positive Long distritoId, @NotNull String direccionReferencia,
            @NotNull @Digits(integer=3,fraction=7) BigDecimal latitud,
            @NotNull @Digits(integer=3,fraction=7) BigDecimal longitud) {
        CrearReporte command() { return new CrearReporte(tipoBarreraId,severidadDeclarada,descripcion,distritoId,direccionReferencia,latitud,longitud); }
    }
    public record Creado(long id, String codigo, String estado, Instant fechaCreacion) {}
    public record FotoRespuesta(long id, String mimeType, long tamanoBytes, String enlace) {}
    public record Detalle(long id, String codigo, long tipoBarreraId, long distritoId, Severidad severidadDeclarada,
            String descripcion, String direccionReferencia, BigDecimal latitud, BigDecimal longitud,
            String estado, Instant fechaCreacion, List<FotoRespuesta> fotos) {
        static Detalle from(Reporte r) {
            return new Detalle(r.id(),r.codigo(),r.tipoBarreraId(),r.distritoId(),r.severidadDeclarada(),r.descripcion(),
                    r.direccionReferencia(),r.latitud(),r.longitud(),r.estado(),r.fechaCreacion(),
                    r.fotos().stream().map(f -> new FotoRespuesta(f.id(),f.mimeType(),f.tamanoBytes(),
                            "/api/reportes/"+r.id()+"/fotos/"+f.id())).toList());
        }
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Creado> crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestPart("datos") Datos datos,
            @RequestPart("foto") MultipartFile foto, MultipartHttpServletRequest request) throws IOException {
        if (request.getMultiFileMap().getOrDefault("foto",List.of()).size()!=1
                || request.getMultiFileMap().entrySet().stream().anyMatch(e -> !Set.of("datos","foto").contains(e.getKey()) || e.getValue().size()!=1)
                || request.getParameterMap().entrySet().stream().anyMatch(e -> !e.getKey().equals("datos") || e.getValue().length!=1)) {
            throw new ReporteException(INVALIDO);
        }
        if (foto.getSize()>ImagenValidada.MAX_BYTES) throw new ReporteException(FOTO_GRANDE);
        byte[] bytes;
        try (var stream=foto.getInputStream()) { bytes=stream.readNBytes(ImagenValidada.MAX_BYTES+1); }
        var r=reportes.crear(datos.command(),bytes,foto.getContentType(),UUID.fromString(jwt.getSubject()));
        return ResponseEntity.created(URI.create("/api/reportes/"+r.id())).body(new Creado(r.id(),r.codigo(),r.estado(),r.fechaCreacion()));
    }
    @GetMapping("/{id}")
    public Detalle detalle(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return Detalle.from(reportes.consultar(id,UUID.fromString(jwt.getSubject())));
    }
    @GetMapping("/{id}/fotos/{fotoId}")
    public ResponseEntity<byte[]> foto(@PathVariable long id, @PathVariable long fotoId, @AuthenticationPrincipal Jwt jwt) {
        var f=reportes.foto(id,fotoId,UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mimeType()))
                .cacheControl(CacheControl.noStore()).header("Content-Disposition","inline; filename=\"evidencia."+(f.mimeType().equals("image/png")?"png":"jpg")+"\"")
                .body(f.contenido());
    }
}

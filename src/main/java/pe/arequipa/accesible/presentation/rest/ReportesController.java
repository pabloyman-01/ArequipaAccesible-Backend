package pe.arequipa.accesible.presentation.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.MultiValueMap;
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
    public record Pagina(List<ResumenReporte> elementos, int limite, boolean hayMas, String siguienteCursor) {}
    @GetMapping
    public ResponseEntity<Pagina> propios(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="20") int limite,
            @RequestParam(required=false) EstadoReporte estado,
            @RequestParam(required=false) Severidad severidadDeclarada,
            @RequestParam(required=false) Long tipoBarreraId,
            @RequestParam(required=false) Long distritoId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required=false) String codigo,
            @RequestParam(required=false) String cursor,
            @RequestParam MultiValueMap<String,String> parametros) {
        Set<String> permitidos=Set.of("limite","estado","severidadDeclarada","tipoBarreraId","distritoId","desde","hasta","codigo","cursor");
        if (parametros.entrySet().stream().anyMatch(p -> !permitidos.contains(p.getKey()) || p.getValue().size()!=1 || p.getValue().getFirst().isBlank())) {
            throw new ReporteException(CONSULTA_INVALIDA);
        }
        var consulta=new ConsultaReportes(limite,estado,tipoBarreraId,distritoId,severidadDeclarada,desde,hasta,codigo,decodificar(cursor));
        var pagina=reportes.listarPropios(consulta,UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Pagina(pagina.elementos(),pagina.limite(),pagina.hayMas(),codificar(pagina.siguiente())));
    }
    private static String codificar(ConsultaReportes.Cursor cursor) {
        return cursor==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(
                (cursor.fecha()+"|"+cursor.id()).getBytes(StandardCharsets.UTF_8));
    }
    private static ConsultaReportes.Cursor decodificar(String cursor) {
        if (cursor==null) return null;
        if (!cursor.matches("[A-Za-z0-9_-]{1,128}")) throw new ReporteException(CONSULTA_INVALIDA);
        try {
            String[] partes=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if (partes.length!=2) throw new ReporteException(CONSULTA_INVALIDA);
            return new ConsultaReportes.Cursor(Instant.parse(partes[0]),Long.parseLong(partes[1]));
        } catch (IllegalArgumentException | DateTimeException e) { throw new ReporteException(CONSULTA_INVALIDA); }
    }
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
    @GetMapping("/{id}/historial")
    public ResponseEntity<List<CambioEstadoReporte>> historial(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reportes.historial(id,UUID.fromString(jwt.getSubject())));
    }
}

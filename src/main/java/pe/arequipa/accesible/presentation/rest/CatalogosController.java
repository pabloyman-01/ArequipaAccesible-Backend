package pe.arequipa.accesible.presentation.rest;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import pe.arequipa.accesible.domain.port.out.ReporteRepositoryPort;

@RestController
@RequestMapping("/api/catalogos")
public class CatalogosController {
    private final ReporteRepositoryPort catalogos;
    public CatalogosController(ReporteRepositoryPort catalogos) { this.catalogos=catalogos; }
    public record Distrito(long id, String nombre) {}
    public record TipoBarrera(long id, String codigo, String nombre, String descripcion) {}
    @GetMapping("/distritos") public List<Distrito> distritos() {
        return catalogos.distritos().stream().map(c -> new Distrito(c.id(),c.nombre())).toList();
    }
    @GetMapping("/tipos-barrera") public List<TipoBarrera> tipos() {
        return catalogos.tiposBarrera().stream().map(c -> new TipoBarrera(c.id(),c.codigo(),c.nombre(),c.descripcion())).toList();
    }
}

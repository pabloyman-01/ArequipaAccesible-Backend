package pe.arequipa.accesible.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import pe.arequipa.accesible.application.usecase.ReportesUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.*;
import pe.arequipa.accesible.infrastructure.storage.SupabaseStorageAdapter;

@Configuration
@EnableScheduling
public class ReportesConfig {
    @Bean ArchivoStoragePort archivoStorage(ObjectMapper json,
            @Value("${app.reportes.habilitado:false}") boolean habilitado,
            @Value("${app.storage.url:}") String url,
            @Value("${app.storage.bucket:reportes-evidencias}") String bucket,
            @Value("${app.storage.secret:}") String secret) {
        if (!habilitado) return new ArchivoStoragePort() {
            private ReporteException error() { return new ReporteException(ReporteException.Motivo.NO_CONFIGURADO); }
            public void subir(String key, ImagenValidada imagen) { throw error(); }
            public void eliminar(String key) { throw error(); }
            public byte[] descargar(String key) { throw error(); }
        };
        URI uri=URI.create(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || !bucket.matches("[a-z0-9-]{1,63}") || secret.isBlank()) {
            throw new IllegalArgumentException("Configure a real HTTPS Storage URL, private bucket and server secret");
        }
        return new SupabaseStorageAdapter(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(),uri,bucket,secret,json);
    }
    @Bean ReportesUseCase reportesUseCase(UsuarioRepositoryPort usuarios, ReporteRepositoryPort reportes,
            ArchivoStoragePort storage, ImagenValidatorPort imagenes,
            @Value("${app.reportes.habilitado:false}") boolean habilitado,
            @Value("${app.reportes.limite-por-minuto:5}") int limite,
            @Value("${app.reportes.lat-min:}") String latMin, @Value("${app.reportes.lat-max:}") String latMax,
            @Value("${app.reportes.lon-min:}") String lonMin, @Value("${app.reportes.lon-max:}") String lonMax) {
        if (limite<1 || limite>60) throw new IllegalArgumentException("Report rate must be 1..60 per minute");
        var area=new AreaPermitida(decimal(latMin),decimal(latMax),decimal(lonMin),decimal(lonMax));
        if (habilitado) {
            if (!area.configurada()) throw new IllegalArgumentException("Approved geographic bounds required");
            area.validar(area.latMin(),area.lonMin());
        }
        return new ReportesUseCase(usuarios,reportes,storage,imagenes,area,Clock.systemUTC(),habilitado,limite);
    }
    private BigDecimal decimal(String value) { return value.isBlank()?null:new BigDecimal(value); }
}

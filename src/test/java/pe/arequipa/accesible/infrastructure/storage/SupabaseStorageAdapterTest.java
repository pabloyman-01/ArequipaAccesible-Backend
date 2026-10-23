package pe.arequipa.accesible.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import pe.arequipa.accesible.domain.model.*;
import static org.assertj.core.api.Assertions.*;

class SupabaseStorageAdapterTest {
    HttpServer server;
    SupabaseStorageAdapter adapter;
    boolean publicBucket;
    int uploadStatus=200;
    String key="evidencias/"+UUID.randomUUID()+".png";
    AtomicInteger uploads=new AtomicInteger();
    byte[] stored;
    String deleteBody;
    String apikey;
    String authorization;
    String upsert;
    String lastPath;
    String contentType;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/storage/v1/",exchange -> {
            String path=exchange.getRequestURI().getPath();
            lastPath=path;
            contentType=exchange.getRequestHeaders().getFirst("Content-Type");
            apikey=exchange.getRequestHeaders().getFirst("apikey");
            authorization=exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body="{}".getBytes(); int status=200;
            if(path.contains("/bucket/")) body=("{\"public\":"+publicBucket+"}").getBytes();
            else if(exchange.getRequestMethod().equals("POST")) {
                uploads.incrementAndGet(); stored=exchange.getRequestBody().readAllBytes();
                upsert=exchange.getRequestHeaders().getFirst("x-upsert"); status=uploadStatus;
            } else if(exchange.getRequestMethod().equals("DELETE")) deleteBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            else if(exchange.getRequestMethod().equals("GET")) body=stored==null?new byte[]{1,2,3}:stored;
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); }
        });
        server.start(); adapter=adapter("sb_secret_test_only");
    }
    SupabaseStorageAdapter adapter(String secret) {
        return new SupabaseStorageAdapter(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"reportes-evidencias",secret,new ObjectMapper());
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void uploadsRawBytesWithoutUpsertAndUsesServerKey() {
        adapter.subir(key,new ImagenValidada(new byte[]{1,2,3},"image/png","png"));
        assertThat(stored).containsExactly((byte)1,(byte)2,(byte)3); assertThat(upsert).isEqualTo("false");
        assertThat(apikey).isEqualTo("sb_secret_test_only"); assertThat(authorization).isNull();
        assertThat(lastPath).isEqualTo("/storage/v1/object/reportes-evidencias/"+key);
        assertThat(contentType).isEqualTo("image/png");
    }
    @Test void legacyServerJwtUsesBearerHeader() {
        adapter("legacy-test-jwt").subir(key,new ImagenValidada(new byte[]{1},"image/png","png"));
        assertThat(authorization).isEqualTo("Bearer legacy-test-jwt");
    }
    @Test void rejectsPublicBucketBeforeUploadingOrDownloading() {
        publicBucket=true;
        assertThatThrownBy(() -> adapter.subir(key,new ImagenValidada(new byte[]{1},"image/png","png"))).isInstanceOf(ReporteException.class);
        assertThatThrownBy(() -> adapter.descargar(key)).isInstanceOf(ReporteException.class);
        assertThat(uploads.get()).isZero();
    }
    @Test void downloadsThroughAuthenticatedRoute() {
        stored=new byte[]{1,2,3}; assertThat(adapter.descargar(key)).containsExactly(stored);
        assertThat(lastPath).isEqualTo("/storage/v1/object/authenticated/reportes-evidencias/"+key);
    }
    @Test void deletesOnlyTheGeneratedKey() throws Exception {
        adapter.eliminar(key);
        assertThat(lastPath).isEqualTo("/storage/v1/object/reportes-evidencias");
        assertThat(new ObjectMapper().readTree(deleteBody).get("prefixes").get(0).asText()).isEqualTo(key);
        assertThatThrownBy(() -> adapter.eliminar("../another-bucket/private" )).isInstanceOf(ReporteException.class);
    }
    @Test void upstreamErrorsAreSanitized() {
        uploadStatus=500;
        assertThatThrownBy(() -> adapter.subir(key,new ImagenValidada(new byte[]{1},"image/png","png")))
                .isInstanceOfSatisfying(ReporteException.class,e -> assertThat(e.motivo()).isEqualTo(ReporteException.Motivo.STORAGE_NO_DISPONIBLE));
    }
    @Test void networkFailureIsNotReportedAsSuccess() {
        server.stop(0);
        assertThatThrownBy(() -> adapter.subir(key,new ImagenValidada(new byte[]{1},"image/png","png"))).isInstanceOf(ReporteException.class);
    }
    @Test void oversizedDownloadIsBounded() {
        stored=new byte[ImagenValidada.MAX_BYTES+1];
        assertThatThrownBy(() -> adapter.descargar(key)).isInstanceOf(ReporteException.class);
    }
}

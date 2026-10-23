package pe.arequipa.accesible.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.ArchivoStoragePort;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.*;

/** HTTP paths follow Supabase Storage's published REST contract. No user JWT is forwarded. */
public class SupabaseStorageAdapter implements ArchivoStoragePort {
    private final HttpClient http;
    private final URI base;
    private final String bucket;
    private final String secret;
    private final ObjectMapper json;
    public SupabaseStorageAdapter(HttpClient http, URI projectUrl, String bucket, String secret, ObjectMapper json) {
        this.http=http; this.base=projectUrl.resolve("/storage/v1/"); this.bucket=bucket; this.secret=secret; this.json=json;
    }
    private void key(String key) {
        if (!key.matches("evidencias/[0-9a-f-]{36}\\.(jpg|png)")) throw new ReporteException(STORAGE_NO_DISPONIBLE);
    }
    private HttpRequest.Builder request(String path) {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).header("apikey",secret);
        // New secret keys are opaque and belong only in apikey; legacy service-role
        // JWTs additionally need Bearer authentication against Storage.
        if (!secret.startsWith("sb_secret_")) builder.header("Authorization","Bearer "+secret);
        return builder;
    }
    private HttpResponse<byte[]> send(HttpRequest request, int maxBytes) {
        try { return http.send(request, info -> new LimitedBody(maxBytes)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ReporteException(STORAGE_NO_DISPONIBLE); }
        catch (Exception e) { throw new ReporteException(STORAGE_NO_DISPONIBLE); }
    }
    private void success(HttpResponse<?> r) {
        if (r.statusCode()<200 || r.statusCode()>=300) throw new ReporteException(STORAGE_NO_DISPONIBLE);
    }
    private void privado() {
        var response=send(request("bucket/"+bucket).GET().build(),64*1024);
        success(response);
        try {
            var metadata=json.readTree(response.body());
            if (!metadata.has("public") || !metadata.get("public").isBoolean() || metadata.get("public").booleanValue()) {
                throw new ReporteException(STORAGE_NO_DISPONIBLE);
            }
        } catch (java.io.IOException e) { throw new ReporteException(STORAGE_NO_DISPONIBLE); }
    }
    @Override public void subir(String key, ImagenValidada imagen) {
        key(key); privado();
        success(send(request("object/"+bucket+"/"+key).header("Content-Type",imagen.mimeType())
                .header("x-upsert","false").header("Cache-Control","private, no-store")
                .POST(HttpRequest.BodyPublishers.ofByteArray(imagen.contenido())).build(),64*1024));
    }
    @Override public void eliminar(String key) {
        key(key);
        try {
            var response=send(request("object/"+bucket).header("Content-Type","application/json")
                    .method("DELETE",HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(java.util.Map.of("prefixes",List.of(key))))).build(),64*1024);
            if (response.statusCode()!=404) success(response);
        } catch (java.io.IOException e) { throw new ReporteException(STORAGE_NO_DISPONIBLE); }
    }
    @Override public byte[] descargar(String key) {
        key(key); privado();
        var response=send(request("object/authenticated/"+bucket+"/"+key).GET().build(),ImagenValidada.MAX_BYTES);
        success(response);
        return response.body();
    }
    /** Bounds response memory, including errors. Request timeout covers the full subscriber. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max;
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int max) { this.max=max; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer:buffers) {
                if (buffer.remaining() > max-out.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Storage response too large")); return;
                }
                byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes); out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable e) { result.completeExceptionally(e); }
        public void onComplete() { result.complete(out.toByteArray()); }
    }
}

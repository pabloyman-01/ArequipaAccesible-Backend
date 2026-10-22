package pe.arequipa.accesible.presentation.rest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiError(Instant timestamp, int status, String code, String message,
                       List<Detail> details, String traceId) {
    public record Detail(String field, String message) {}
    public static ApiError of(int status, String code, String message, List<Detail> details) {
        return new ApiError(Instant.now(), status, code, message, details, UUID.randomUUID().toString());
    }
    public static ApiError of(int status, String code, String message) { return of(status,code,message,List.of()); }
}

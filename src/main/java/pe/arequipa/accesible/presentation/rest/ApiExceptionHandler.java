package pe.arequipa.accesible.presentation.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import pe.arequipa.accesible.domain.model.PerfilException;
import pe.arequipa.accesible.domain.model.ReporteException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ReporteException.class)
    ResponseEntity<ApiError> reporte(ReporteException e) {
        return switch(e.motivo()) {
            case CONSULTA_INVALIDA -> error(400,"FILTROS_INVALIDOS","Revise filtros, límite y cursor");
            case INVALIDO -> error(422,"REPORTE_INVALIDO","Revise los datos, catálogos activos y área permitida");
            case FOTO_GRANDE -> error(413,"FOTO_DEMASIADO_GRANDE","La imagen supera 5 MiB");
            case FOTO_INVALIDA -> error(415,"FOTO_INVALIDA","Se requiere una imagen JPEG o PNG válida y MIME coincidente");
            case SIN_PERMISO -> error(403,"ACCESO_DENEGADO","Se requiere un perfil activo con permisos");
            case NO_ENCONTRADO -> error(404,"NO_ENCONTRADO","Recurso no encontrado");
            case NO_CONFIGURADO -> error(503,"REPORTES_NO_CONFIGURADO","Registro de reportes pendiente de configuración");
            case STORAGE_NO_DISPONIBLE -> error(503,"STORAGE_NO_DISPONIBLE","Almacenamiento temporalmente no disponible");
            case LIMITE_EXCEDIDO -> ResponseEntity.status(429).header("Retry-After","60")
                    .body(ApiError.of(429,"LIMITE_EXCEDIDO","Límite de reportes por minuto alcanzado"));
            case CONFLICTO -> error(409,"REPORTE_CONFLICTO","No se pudo confirmar el reporte");
        };
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> grande() { return error(413,"FOTO_DEMASIADO_GRANDE","La imagen supera 5 MiB o la petición supera 6 MiB"); }
    @ExceptionHandler({MissingServletRequestPartException.class,MultipartException.class})
    ResponseEntity<ApiError> multipart() { return error(400,"PETICION_INVALIDA","Revise partes multipart e identificadores"); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> parametro() { return error(400,"PETICION_INVALIDA","Revise los parámetros y sus formatos"); }
    @ExceptionHandler(PerfilException.class)
    ResponseEntity<ApiError> perfil(PerfilException e) {
        return switch(e.motivo()) {
            case NO_ENCONTRADO -> error(404,"PERFIL_NO_ENCONTRADO","Complete su perfil con PUT /api/usuarios/me");
            case SUSPENDIDO -> error(403,"CUENTA_SUSPENDIDA","Cuenta suspendida");
            case DISTRITO_INVALIDO -> error(422,"DISTRITO_INVALIDO","Distrito inexistente o inactivo");
        };
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validacion(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ApiError.of(400,"DATOS_INVALIDOS","Revise los campos",
                e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(),f.getDefaultMessage())).toList()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> json() { return error(400,"JSON_INVALIDO","JSON incorrecto o campos no permitidos"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflicto(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/reportes")
                ? error(409,"REPORTE_CONFLICTO","No se pudo confirmar el reporte")
                : error(409,"PERFIL_CONFLICTO","Los datos del perfil presentan un conflicto");
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denegado() { return error(403,"ACCESO_DENEGADO","Permisos insuficientes"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noExiste() { return error(404,"NO_ENCONTRADO","Recurso no encontrado"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> metodo() { return error(405,"METODO_NO_PERMITIDO","Método no permitido"); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> formato() { return error(415,"FORMATO_NO_PERMITIDO","Formato no permitido"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> inesperado(Exception e) {
        var body = ApiError.of(500,"ERROR_INTERNO","Error interno");
        // No exception messages/stack traces: drivers can include personal data and SQL values.
        LOG.error("request_failed traceId={} type={}", body.traceId(), e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status,code,message));
    }
}

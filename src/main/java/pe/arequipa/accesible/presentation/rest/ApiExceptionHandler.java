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

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
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
    ResponseEntity<ApiError> conflicto() { return error(409,"PERFIL_CONFLICTO","Los datos del perfil presentan un conflicto"); }
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

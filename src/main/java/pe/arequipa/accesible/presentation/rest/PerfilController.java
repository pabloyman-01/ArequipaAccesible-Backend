package pe.arequipa.accesible.presentation.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pe.arequipa.accesible.application.command.*;
import pe.arequipa.accesible.application.usecase.PerfilUseCase;
import pe.arequipa.accesible.domain.model.UsuarioPerfil;
import pe.arequipa.accesible.domain.model.Rol;

@RestController
@RequestMapping("/api/usuarios/me")
public class PerfilController {
    private final PerfilUseCase perfiles;
    public PerfilController(PerfilUseCase perfiles) { this.perfiles = perfiles; }
    public record Alta(@NotBlank @Size(max=100) String nombres, @NotBlank @Size(max=100) String apellidos,
                       @NotNull @Pattern(regexp="DNI|CE|PASAPORTE") String tipoDocumento,
                       @NotBlank @Pattern(regexp="[A-Za-z0-9]{6,20}") String documento,
                       @Pattern(regexp="[+0-9 ()-]{6,20}") String telefono, @Positive Long distritoId) {
        @AssertTrue(message="DNI debe contener ocho dígitos")
        public boolean isDocumentoValido() { return !"DNI".equals(tipoDocumento) || (documento != null && documento.matches("[0-9]{8}")); }
    }
    public record Cambio(@Pattern(regexp="(?s).*\\S.*") @Size(max=100) String nombres,
                         @Pattern(regexp="(?s).*\\S.*") @Size(max=100) String apellidos,
                         @Pattern(regexp="[+0-9 ()-]{6,20}") String telefono, @Positive Long distritoId) {}
    public record Respuesta(UUID id, String nombres, String apellidos, String tipoDocumento, String documentoEnmascarado,
                            String email, String telefono, Long distritoId, String estadoCuenta,
                            String estadoVerificacion, Instant creadoEn, Set<Rol> roles) {
        static Respuesta from(UsuarioPerfil p) {
            String d = p.documento();
            return new Respuesta(p.id(),p.nombres(),p.apellidos(),p.tipoDocumento(),
                    "*".repeat(Math.max(0,d.length()-4))+d.substring(Math.max(0,d.length()-4)),
                    p.email(),p.telefono(),p.distritoId(),p.estadoCuenta(),p.estadoVerificacion(),p.creadoEn(),p.roles());
        }
    }
    @GetMapping
    public Respuesta me(@AuthenticationPrincipal Jwt jwt) { return Respuesta.from(perfiles.consultar(UUID.fromString(jwt.getSubject()))); }
    @PutMapping
    public Respuesta alta(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Alta r) {
        return Respuesta.from(perfiles.registrar(UUID.fromString(jwt.getSubject()),jwt.getClaimAsString("email"),
                new DatosPerfil(r.nombres(),r.apellidos(),r.tipoDocumento(),r.documento(),r.telefono(),r.distritoId())));
    }
    @PatchMapping
    public Respuesta actualizar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Cambio r) {
        return Respuesta.from(perfiles.actualizar(UUID.fromString(jwt.getSubject()),
                new ActualizarPerfil(r.nombres(),r.apellidos(),r.telefono(),r.distritoId())));
    }
}

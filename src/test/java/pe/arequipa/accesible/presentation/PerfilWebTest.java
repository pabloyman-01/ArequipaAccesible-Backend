package pe.arequipa.accesible.presentation;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import pe.arequipa.accesible.application.usecase.PerfilUseCase;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.UsuarioRepositoryPort;
import pe.arequipa.accesible.infrastructure.security.*;
import pe.arequipa.accesible.presentation.rest.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=PerfilController.class,properties={"app.require-https=false","app.cors-origins=https://frontend.example.test"})
@Import({SecurityConfig.class,DatabaseJwtConverter.class})
class PerfilWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UsuarioRepositoryPort repo;
    @MockitoBean PerfilUseCase service;
    final UUID id = UUID.randomUUID();
    UsuarioPerfil perfil(String estado, Rol rol) {
        return new UsuarioPerfil(id,"Ana","Perez","DNI","12345678","ana@example.test",null,null,estado,"NO_VERIFICADO",Instant.now(),Set.of(rol));
    }
    @BeforeEach void setup() {
        var jwt = Jwt.withTokenValue("valid").header("alg","RS256").subject(id.toString())
                .claim("email","ana@example.test").claim("user_metadata",Map.of("roles",List.of("ADMINISTRADOR")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(decoder.decode("valid")).thenReturn(jwt);
        when(repo.buscar(id)).thenReturn(Optional.of(perfil("ACTIVA",Rol.CIUDADANO)));
        when(service.consultar(id)).thenReturn(perfil("ACTIVA",Rol.CIUDADANO));
    }
    @Test void missingJwt401() throws Exception { mvc.perform(get("/api/usuarios/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NO_AUTENTICADO")); }
    @Test void invalidJwt401() throws Exception {
        when(decoder.decode("bad")).thenThrow(new BadJwtException("bad"));
        mvc.perform(get("/api/usuarios/me").header("Authorization","Bearer bad")).andExpect(status().isUnauthorized());
    }
    @Test void ownProfileMasked() throws Exception {
        mvc.perform(get("/api/usuarios/me").header("Authorization","Bearer valid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.documentoEnmascarado").value("****5678")).andExpect(jsonPath("$.documento").doesNotExist());
        verify(service).consultar(id);
    }
    @Test void userMetadataCannotGrantAdmin() throws Exception {
        mvc.perform(get("/api/admin/usuarios").header("Authorization","Bearer valid"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }
    @Test void suspended403() throws Exception {
        when(repo.buscar(id)).thenReturn(Optional.of(perfil("SUSPENDIDA",Rol.ADMINISTRADOR)));
        mvc.perform(get("/api/usuarios/me").header("Authorization","Bearer valid"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CUENTA_SUSPENDIDA"));
        verifyNoInteractions(service);
    }
    @Test void rolesCannotBePatched() throws Exception {
        mvc.perform(patch("/api/usuarios/me").header("Authorization","Bearer valid").contentType("application/json")
                .content("{\"roles\":[\"ADMINISTRADOR\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JSON_INVALIDO"));
    }
    @Test void bodyCannotSelectAnotherUser() throws Exception {
        mvc.perform(patch("/api/usuarios/me").header("Authorization","Bearer valid").contentType("application/json")
                .content("{\"id\":\""+UUID.randomUUID()+"\",\"nombres\":\"Otra\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void arbitraryUserPathNotAvailable() throws Exception {
        mvc.perform(get("/api/usuarios/"+UUID.randomUUID()).header("Authorization","Bearer valid"))
                .andExpect(status().isNotFound());
    }
    @Test void malformedJson400() throws Exception {
        mvc.perform(put("/api/usuarios/me").header("Authorization","Bearer valid").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
    }
    @Test void invalidDni400() throws Exception {
        mvc.perform(put("/api/usuarios/me").header("Authorization","Bearer valid").contentType("application/json")
                .content("{\"nombres\":\"Ana\",\"apellidos\":\"Perez\",\"tipoDocumento\":\"DNI\",\"documento\":\"ABCDEFGH\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void trustedCors() throws Exception {
        mvc.perform(options("/api/usuarios/me").header("Origin","https://frontend.example.test")
                .header("Access-Control-Request-Method","PATCH").header("Access-Control-Request-Headers","Authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://frontend.example.test"));
    }
    @Test void otherCorsDenied() throws Exception {
        mvc.perform(options("/api/usuarios/me").header("Origin","https://attacker.example.test")
                .header("Access-Control-Request-Method","PATCH"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}

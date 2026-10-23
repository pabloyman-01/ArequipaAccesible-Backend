package pe.arequipa.accesible.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import pe.arequipa.accesible.presentation.rest.ApiError;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http, DatabaseJwtConverter converter, ObjectMapper json,
                                      @Value("${app.require-https:true}") boolean https) throws Exception {
        if (https) http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        http.csrf(csrf -> csrf.disable()).cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/catalogos/distritos","/api/catalogos/tipos-barrera").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/reportes").hasRole("CIUDADANO")
                .requestMatchers("/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").hasRole("ADMINISTRADOR")
                .requestMatchers("/api/admin/**").hasRole("ADMINISTRADOR")
                .requestMatchers("/api/usuarios/me").authenticated()
                .anyRequest().hasAnyRole("CIUDADANO","ADMINISTRADOR"))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req,res,ex) -> {
                    boolean suspended = ex instanceof OAuth2AuthenticationException oauth
                            && "cuenta_suspendida".equals(oauth.getError().getErrorCode());
                    if (!suspended) res.setHeader("WWW-Authenticate","Bearer");
                    write(json,res,suspended ? 403 : 401,suspended ? "CUENTA_SUSPENDIDA" : "NO_AUTENTICADO",
                            suspended ? "Cuenta suspendida" : "Se requiere un JWT válido");
                }))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req,res,ex) -> {
                    res.setHeader("WWW-Authenticate","Bearer");
                    write(json,res,401,"NO_AUTENTICADO","Se requiere un JWT válido");
                })
                .accessDeniedHandler((req,res,ex) -> write(json,res,403,"ACCESO_DENEGADO","Permisos insuficientes")));
        return http.build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins:}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        if (config.getAllowedOrigins().contains("*")) throw new IllegalArgumentException("Explicit CORS origins required");
        config.setAllowedMethods(List.of("GET","PUT","PATCH","POST","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type"));
        config.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**",config);
        return source;
    }
    private static void write(ObjectMapper json, HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        json.writeValue(res.getOutputStream(),ApiError.of(status,code,message));
    }
}

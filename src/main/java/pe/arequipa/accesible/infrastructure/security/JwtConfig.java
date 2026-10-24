package pe.arequipa.accesible.infrastructure.security;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.issuer}") String issuer,
                          @Value("${app.jwt.jwks-uri}") String jwks,
                          @Value("${app.jwt.audience}") String audience) {
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) throw new IllegalArgumentException("JWT configuration required");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithms(algorithms -> { algorithms.add(SignatureAlgorithm.RS256); algorithms.add(SignatureAlgorithm.ES256); })
                .build();
        decoder.setJwtValidator(validators(issuer,audience));
        return decoder;
    }
    public static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                String sub = jwt.getSubject();
                if (sub == null || !UUID.fromString(sub).toString().equalsIgnoreCase(sub)
                        || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null
                        || !jwt.getAudience().contains(audience)
                        || !"authenticated".equals(jwt.getClaimAsString("role"))
                        || Boolean.TRUE.equals(jwt.getClaimAsBoolean("is_anonymous"))
                        || jwt.getClaimAsString("email") == null || jwt.getClaimAsString("email").isBlank()) {
                    return invalid();
                }
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException e) { return invalid(); }
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims);
    }
    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","JWT inválido",null));
    }
}

package pe.arequipa.accesible.infrastructure.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class JwtVerificationTest {
    static HttpServer server;
    static RSAKey key;
    static JwtDecoder decoder;
    static final String ISSUER = "https://identity.example.test/auth/v1";
    @BeforeAll static void setup() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-only").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        decoder = new JwtConfig().jwtDecoder(ISSUER,"http://127.0.0.1:"+server.getAddress().getPort()+"/jwks","authenticated");
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }
    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(UUID.randomUUID().toString())
                .audience("authenticated").issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("email","ana@example.test").claim("role","authenticated");
    }
    String sign(JWTClaimsSet.Builder claims, RSAKey signingKey) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-only").build(),claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
    @Test void validSignatureAndClaims() throws Exception { assertThat(decoder.decode(sign(claims(),key)).getSubject()).isNotBlank(); }
    @Test void invalidSignature() throws Exception {
        var other = new RSAKeyGenerator(2048).generate();
        assertThatThrownBy(() -> decoder.decode(sign(claims(),other))).isInstanceOf(JwtException.class);
    }
    @Test void expired() { assertThatThrownBy(() -> decoder.decode(sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(120))),key))).isInstanceOf(JwtException.class); }
    @Test void wrongIssuer() { assertThatThrownBy(() -> decoder.decode(sign(claims().issuer("https://attacker.example.test"),key))).isInstanceOf(JwtException.class); }
    @Test void wrongAudience() { assertThatThrownBy(() -> decoder.decode(sign(claims().audience("another-app"),key))).isInstanceOf(JwtException.class); }
    @Test void missingExpiration() { assertThatThrownBy(() -> decoder.decode(sign(claims().expirationTime(null),key))).isInstanceOf(JwtException.class); }
    @Test void invalidSubject() { assertThatThrownBy(() -> decoder.decode(sign(claims().subject("not-a-uuid"),key))).isInstanceOf(JwtException.class); }
    @Test void anonymousAccount() { assertThatThrownBy(() -> decoder.decode(sign(claims().claim("is_anonymous",true),key))).isInstanceOf(JwtException.class); }
    @Test void serviceRoleToken() { assertThatThrownBy(() -> decoder.decode(sign(claims().claim("role","service_role"),key))).isInstanceOf(JwtException.class); }
    @Test void futureNotBefore() { assertThatThrownBy(() -> decoder.decode(sign(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(300))),key))).isInstanceOf(JwtException.class); }
}

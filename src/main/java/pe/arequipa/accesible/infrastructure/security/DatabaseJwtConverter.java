package pe.arequipa.accesible.infrastructure.security;

import java.util.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import pe.arequipa.accesible.domain.port.out.UsuarioRepositoryPort;

@Component
public class DatabaseJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UsuarioRepositoryPort usuarios;
    public DatabaseJwtConverter(UsuarioRepositoryPort usuarios) { this.usuarios = usuarios; }
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        var perfil = usuarios.buscar(UUID.fromString(jwt.getSubject()));
        if (perfil.isPresent() && !perfil.get().activo()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("cuenta_suspendida"));
        }
        var authorities = perfil.stream().flatMap(p -> p.roles().stream())
                .map(rol -> new SimpleGrantedAuthority("ROLE_"+rol.name())).toList();
        return new JwtAuthenticationToken(jwt,authorities,jwt.getSubject());
    }
}

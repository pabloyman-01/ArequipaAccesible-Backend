package pe.arequipa.accesible.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.*;
import pe.arequipa.accesible.application.usecase.PerfilUseCase;
import pe.arequipa.accesible.domain.port.out.UsuarioRepositoryPort;

@Configuration
public class ApplicationConfig {
    @Bean PerfilUseCase perfilUseCase(UsuarioRepositoryPort usuarios) { return new PerfilUseCase(usuarios, Clock.systemUTC()); }
}

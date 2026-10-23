package pe.arequipa.accesible.infrastructure.config;

import org.springframework.context.annotation.*;
import pe.arequipa.accesible.application.usecase.AdministracionUseCase;
import pe.arequipa.accesible.domain.port.out.*;

@Configuration
public class AdministracionConfig {
    @Bean AdministracionUseCase administracionUseCase(UsuarioRepositoryPort usuarios, ReporteRepositoryPort reportes, AdministracionRepositoryPort admin) {
        return new AdministracionUseCase(usuarios,reportes,admin);
    }
}

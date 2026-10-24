package pe.arequipa.accesible.domain.port.out;

import pe.arequipa.accesible.domain.model.*;

public interface DashboardRepositoryPort {
    ResumenDashboard resumir(ConsultaDashboard consulta);
}

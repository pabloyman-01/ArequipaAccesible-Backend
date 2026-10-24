package pe.arequipa.accesible.infrastructure.persistence;

import jakarta.persistence.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.UsuarioRepositoryPort;

@Repository
@Transactional
public class JpaUsuarioAdapter implements UsuarioRepositoryPort {
    @PersistenceContext private EntityManager em;
    @Override
    @Transactional(readOnly = true)
    public Optional<UsuarioPerfil> buscar(UUID id) {
        return Optional.ofNullable(em.find(UsuarioEntity.class, id)).map(this::map);
    }
    @Override
    public UsuarioPerfil registrarSiAusente(UsuarioPerfil p) {
        // Serialize first registration by verified subject before checking multiple UNIQUE indexes.
        // ON CONFLICT(id) alone can still race on documento/email during speculative insertion.
        em.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(cast(:id AS text),0))")
                .setParameter("id",p.id().toString()).getSingleResult();
        int inserted = em.createNativeQuery("""
            INSERT INTO accesible.usuarios(id,nombres,apellidos,tipo_documento,documento,email,telefono,distrito_id)
            VALUES (:id,:n,:a,:tipo,:doc,:email,:tel,:distrito) ON CONFLICT (id) DO NOTHING
            """).setParameter("id",p.id()).setParameter("n",p.nombres()).setParameter("a",p.apellidos())
                .setParameter("tipo",p.tipoDocumento()).setParameter("doc",p.documento())
                .setParameter("email",p.email()).setParameter("tel",p.telefono())
                .setParameter("distrito",p.distritoId()).executeUpdate();
        if (inserted == 1) {
            em.createNativeQuery("""
                INSERT INTO accesible.usuario_roles(usuario_id,rol_id)
                SELECT :id,id FROM accesible.roles WHERE codigo='CIUDADANO'
                """).setParameter("id",p.id()).executeUpdate();
        }
        return buscar(p.id()).orElseThrow();
    }
    @Override
    public UsuarioPerfil actualizarContacto(UUID id, String nombres, String apellidos, String telefono, Long distritoId) {
        var e = em.find(UsuarioEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (e == null) throw new PerfilException(PerfilException.Motivo.NO_ENCONTRADO);
        if (!"ACTIVA".equals(e.estadoCuenta)) throw new PerfilException(PerfilException.Motivo.SUSPENDIDO);
        if (nombres != null) e.nombres = nombres;
        if (apellidos != null) e.apellidos = apellidos;
        if (telefono != null) e.telefono = telefono;
        if (distritoId != null) e.distritoId = distritoId;
        return map(e);
    }
    @Override
    @Transactional(readOnly = true)
    public boolean distritoActivo(Long id) {
        return ((Number)em.createNativeQuery("SELECT count(*) FROM accesible.distritos WHERE id=:id AND activo")
                .setParameter("id",id).getSingleResult()).intValue() == 1;
    }
    private UsuarioPerfil map(UsuarioEntity e) {
        var rows = em.createNativeQuery("""
            SELECT r.codigo FROM accesible.roles r JOIN accesible.usuario_roles ur ON r.id=ur.rol_id
            WHERE ur.usuario_id=:id
            """, String.class).setParameter("id",e.id).getResultList();
        Set<Rol> roles = new HashSet<>();
        for (Object row : rows) roles.add(Rol.valueOf(row.toString()));
        return new UsuarioPerfil(e.id,e.nombres,e.apellidos,e.tipoDocumento,e.documento,e.email,e.telefono,
                e.distritoId,e.estadoCuenta,e.estadoVerificacion,e.creadoEn,roles);
    }
}

package pe.arequipa.accesible.domain.model;

public class PerfilException extends RuntimeException {
    public enum Motivo { NO_ENCONTRADO, SUSPENDIDO, DISTRITO_INVALIDO }
    private final Motivo motivo;
    public PerfilException(Motivo motivo) { super(motivo.name()); this.motivo = motivo; }
    public Motivo motivo() { return motivo; }
}

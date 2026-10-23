package pe.arequipa.accesible.domain.model;

public class AdministracionException extends RuntimeException {
    public enum Motivo { SIN_PERMISO, NO_ENCONTRADO, TRANSICION_INVALIDA, ESTADO_CONFLICTO, MOTIVO_INVALIDO, CONSULTA_INVALIDA }
    private final Motivo motivo;
    public AdministracionException(Motivo motivo) { super(motivo.name()); this.motivo=motivo; }
    public Motivo motivo() { return motivo; }
}

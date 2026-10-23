package pe.arequipa.accesible.domain.model;

public class ReporteException extends RuntimeException {
    public enum Motivo { CONSULTA_INVALIDA, INVALIDO, FOTO_GRANDE, FOTO_INVALIDA, SIN_PERMISO, NO_ENCONTRADO,
        NO_CONFIGURADO, STORAGE_NO_DISPONIBLE, LIMITE_EXCEDIDO, CONFLICTO }
    private final Motivo motivo;
    public ReporteException(Motivo motivo) { super(motivo.name()); this.motivo = motivo; }
    public Motivo motivo() { return motivo; }
}

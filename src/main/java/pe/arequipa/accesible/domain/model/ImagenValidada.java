package pe.arequipa.accesible.domain.model;

public record ImagenValidada(byte[] contenido, String mimeType, String extension) {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
}

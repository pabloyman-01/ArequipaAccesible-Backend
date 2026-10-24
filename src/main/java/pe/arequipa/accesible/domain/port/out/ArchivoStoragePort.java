package pe.arequipa.accesible.domain.port.out;

import pe.arequipa.accesible.domain.model.ImagenValidada;

public interface ArchivoStoragePort {
    void subir(String objectKey, ImagenValidada imagen);
    void eliminar(String objectKey);
    byte[] descargar(String objectKey);
}

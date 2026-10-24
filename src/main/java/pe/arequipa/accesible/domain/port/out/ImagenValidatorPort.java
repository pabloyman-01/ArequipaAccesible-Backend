package pe.arequipa.accesible.domain.port.out;

import pe.arequipa.accesible.domain.model.ImagenValidada;

public interface ImagenValidatorPort {
    ImagenValidada validar(byte[] contenido, String mimeDeclarado);
}

package pe.arequipa.accesible.infrastructure.storage;

import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import pe.arequipa.accesible.domain.model.*;
import pe.arequipa.accesible.domain.port.out.ImagenValidatorPort;
import static pe.arequipa.accesible.domain.model.ReporteException.Motivo.*;

@Component
public class ImagenValidator implements ImagenValidatorPort {
    @Override public ImagenValidada validar(byte[] bytes, String mime) {
        if (bytes == null || bytes.length==0) throw new ReporteException(FOTO_INVALIDA);
        if (bytes.length > ImagenValidada.MAX_BYTES) throw new ReporteException(FOTO_GRANDE);
        String real;
        if (bytes.length>=12 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
                && Arrays.equals(Arrays.copyOfRange(bytes,bytes.length-12,bytes.length),new byte[]{0,0,0,0,73,69,78,68,(byte)174,66,96,(byte)130})) real="image/png";
        else if (bytes.length>=4 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255
                && (bytes[bytes.length-2]&255)==255 && (bytes[bytes.length-1]&255)==217) real="image/jpeg";
        else throw new ReporteException(FOTO_INVALIDA);
        if (!real.equals(mime)) throw new ReporteException(FOTO_INVALIDA);
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ReporteException(FOTO_INVALIDA);
            var reader = readers.next();
            try {
                reader.setInput(stream,true,true);
                int w=reader.getWidth(0), h=reader.getHeight(0);
                if (w<=0 || h<=0 || (long)w*h>25_000_000) throw new ReporteException(FOTO_INVALIDA);
                String format=reader.getFormatName();
                if (!(real.equals("image/png") && format.equalsIgnoreCase("png"))
                        && !(real.equals("image/jpeg") && format.equalsIgnoreCase("jpeg"))) throw new ReporteException(FOTO_INVALIDA);
                var decoded = reader.read(0);
                if (decoded==null) throw new ReporteException(FOTO_INVALIDA);
                decoded.flush();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) { throw new ReporteException(FOTO_INVALIDA); }
        return new ImagenValidada(bytes,real,real.equals("image/png")?"png":"jpg");
    }
}

package pe.arequipa.accesible.infrastructure.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import pe.arequipa.accesible.domain.model.*;
import static org.assertj.core.api.Assertions.*;

class ImagenValidatorTest {
    final ImagenValidator validator=new ImagenValidator();
    byte[] image(String format) throws Exception {
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,out); return out.toByteArray();
    }
    @Test void pngAndJpegAreDetectedAndDecoded() throws Exception {
        assertThat(validator.validar(image("png"),"image/png").extension()).isEqualTo("png");
        assertThat(validator.validar(image("jpeg"),"image/jpeg").extension()).isEqualTo("jpg");
    }
    @Test void declaredMimeMustMatchTheActualFormat() throws Exception {
        byte[] bytes=image("png"); assertThatThrownBy(() -> validator.validar(bytes,"image/jpeg")).isInstanceOf(ReporteException.class);
    }
    @Test void forgedSignatureAndCorruptionAreRejected() throws Exception {
        byte[] bytes=image("png"); for(int i=8;i<bytes.length-12;i++) bytes[i]=0;
        assertThatThrownBy(() -> validator.validar(bytes,"image/png")).isInstanceOf(ReporteException.class);
    }
    @Test void truncatedImageIsRejected() throws Exception {
        byte[] bytes=image("jpeg"); assertThatThrownBy(() -> validator.validar(java.util.Arrays.copyOf(bytes,bytes.length-2),"image/jpeg")).isInstanceOf(ReporteException.class);
    }
    @Test void emptyAndOversizedAreRejected() {
        assertThatThrownBy(() -> validator.validar(new byte[0],"image/png")).isInstanceOf(ReporteException.class);
        assertThatThrownBy(() -> validator.validar(new byte[ImagenValidada.MAX_BYTES+1],"image/png"))
                .isInstanceOfSatisfying(ReporteException.class,e -> assertThat(e.motivo()).isEqualTo(ReporteException.Motivo.FOTO_GRANDE));
    }
    @Test void excessivePixelCountIsRejectedWithoutDecoding() throws Exception {
        // Alter a valid PNG IHDR width/height. The decoder exposes dimensions before allocating pixels.
        byte[] bytes=image("png");
        java.nio.ByteBuffer.wrap(bytes,16,8).putInt(100_000).putInt(100_000);
        assertThatThrownBy(() -> validator.validar(bytes,"image/png")).isInstanceOf(ReporteException.class);
    }
}

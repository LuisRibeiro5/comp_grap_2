package aula.util;

import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL12;

import static org.lwjgl.opengl.GL11.*;

public class TextureLoader {
    private static final int BYTES_PER_PIXEL = 4;//3 for RGB, 4 for RGBA
       
    public static int loadTexture(BufferedImage image){
    	
    	System.out.println(" "+image.getWidth() +" "+ image.getHeight());

            // BufferedImage fornece pixels no formato ARGB inteiro; primeiro copiamos
            // todos para um array Java e depois os reorganizamos como bytes RGBA,
            // formato que será enviado ao OpenGL mais abaixo.
          int[] pixels = new int[image.getWidth() * image.getHeight()];
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());

            ByteBuffer buffer = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * BYTES_PER_PIXEL); //4 for RGBA, 3 for RGB

            for(int y = 0; y < image.getHeight(); y++){
                for(int x = 0; x < image.getWidth(); x++){
                    int pixel = pixels[y * image.getWidth() + x];
                    buffer.put((byte) ((pixel >> 16) & 0xFF));     // Red component
                    buffer.put((byte) ((pixel >> 8) & 0xFF));      // Green component
                    buffer.put((byte) (pixel & 0xFF));               // Blue component
                    buffer.put((byte) ((pixel >> 24) & 0xFF));    // Alpha component. Only for RGBA
                }
            }

            // flip prepara o buffer para leitura: até aqui escrevemos pixels nele;
            // agora o OpenGL precisa ler do início até o último byte preenchido.
            buffer.flip();

          // Cria um identificador OpenGL e o torna a textura ativa para configuração.
          // O ID retornado é guardado por Main e vinculado antes de desenhar o modelo.
          int textureID = glGenTextures(); //gera ID da textura
            glBindTexture(GL_TEXTURE_2D, textureID); //faz o Bind da textura para OpenGL,
                                            // tornando-a ativa para configuração e envio de pixels

            //Setup wrap mode
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            //Setup texture scaling filtering
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            // Envia os pixels para a GPU. A partir daí a imagem pode ser amostrada
            // durante o desenho usando as coordenadas UV fornecidas pelo modelo.
            //Send texel data to OpenGL
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.getWidth(),
                    image.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

            //retorne o id da textura para que possamos fazer o bind dela novamente mais tarde

          return textureID;
       }

       public static BufferedImage loadImage(String loc)
       {
            try {
               return ImageIO.read(new FileInputStream(loc));
            } catch (IOException e) {
                //tratamento de erros
            }
           return null;
       }
}

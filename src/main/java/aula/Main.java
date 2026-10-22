package aula;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import aula.md2.Md2Model;
import aula.obj.ObjModel;
import aula.util.PcxImageLoader;
import aula.util.TextureLoader;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

public class Main {

	public static void main(String[] args) throws Exception {
		// o programa lê os argumentos recebidos. Se o primeiro arquivo termina em .md2, 
		// escolhe o modo de animação e espera o segundo argumento como textura na linha 55
		Path modelPath = Paths.get(args.length > 0 ? args[0] : "../objs/x-35_obj.obj");
		boolean md2 = modelPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md2");
		if (md2 && args.length < 2) {
			throw new IllegalArgumentException(
					"Uso MD2: mvn compile exec:java -Dexec.args=\"caminho/modelo.md2 caminho/textura.png\"");
		}
		if (!glfwInit())
			throw new IllegalStateException("GLFW nao iniciou");
		
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
		long window = glfwCreateWindow(1024, 768,
				md2 ? "T2 - Animacao MD2 (LWJGL VBO)" : "Aula VBO - OBJ (sem shaders)", 0, 0);
		if (window == 0)
			throw new IllegalStateException("Falha ao criar janela");
		glfwMakeContextCurrent(window);
		glfwSwapInterval(1);
		GL.createCapabilities();
		glEnable(GL_DEPTH_TEST);
		glActiveTexture(GL_TEXTURE0);
		
		glClearColor(.5f, .5f, .5f, 1);
		// A partir daqui o programa escolhe o fluxo pelo tipo do modelo:
		// MD2 carrega também uma skin e executa animações; OBJ segue o fluxo antigo.
		//Como eh feito o model do md2 para virar uma malha? leitura em Md2Model.load
		try {
			if (md2) {
				// runMd2 carrega a imagem, chama Md2Model.load para interpretar a malha
				// e mantém o ciclo de atualização e desenho até a janela ser fechada.
				runMd2(window, modelPath, Paths.get(args[1]));
			} else {
				runObj(window, modelPath);
			}
		} finally {
			glfwDestroyWindow(window);
			glfwTerminate();
		}
	}

	private static void runObj(long window, Path path) throws IOException {
		ObjModel mesh = new ObjModel(path.toString());
		System.out.println("mesh " + mesh.drawboundingbox());

		BufferedImage img = ImageIO.read(new File("src/main/resources/saida_textura_03___Default.png"));
		int textureId = TextureLoader.loadTexture(img);
		BufferedImage img2 = ImageIO.read(new File("src/main/resources/saida_textura_glass.png"));
		int textureId2 = TextureLoader.loadTexture(img2);
		mesh.setMaterialTexture("03___Default", textureId);
		mesh.setMaterialTexture("glass", textureId2);

		System.out.println("Vertices renderizados: " + mesh.f.size() + " | OBJ: " + path);
		double start = glfwGetTime();
		while (!glfwWindowShouldClose(window)) {
			if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
				glfwSetWindowShouldClose(window, true);
			}
			int[] w = new int[1], h = new int[1];
			glfwGetFramebufferSize(window, w, h);
			if (h[0] == 0) {
				glfwPollEvents();
				continue;
			}

			glViewport(0, 0, w[0], h[0]);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
			glMatrixMode(GL_PROJECTION);
			glLoadIdentity();
			double aspect = (double) w[0] / h[0];
			glFrustum(-aspect * .7, aspect * .7, -.7, .7, 1, 100);
			glMatrixMode(GL_MODELVIEW);
			glLoadIdentity();
			glTranslatef(0, 0, -4);
			glRotatef((float) ((glfwGetTime() - start) * 25), 0, 1, 0);
			glRotatef(glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS ? 0 : 20, 1, 0, 0);
			glPolygonMode(GL_FRONT_AND_BACK,
					glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS ? GL_LINE : GL_FILL);
			glColor3f(1, 1, 1);
			glEnable(GL_COLOR_MATERIAL);
			glEnable(GL_TEXTURE_2D);
			glBindTexture(GL_TEXTURE_2D, textureId);
			glTranslatef(0, -1, 0);
			glScalef(0.05f, 0.05f, 0.05f);
			mesh.draw();
			glfwSwapBuffers(window);
			glfwPollEvents();
		}
		glDeleteTextures(textureId);
		glDeleteTextures(textureId2);
	}

	private static void runMd2(long window, Path modelPath, Path texturePath) throws IOException {
		//ciclo principal do modo md2, onde em cada volta do ciclo eh atualizado as animacoes, '
		// limpa a tela e prepara as matrizes e camera, prepara as operacoes 
		// e ativa a textura e desnha
		// Primeiro transforma o arquivo de imagem em BufferedImage. Para PCX, a conversão
		// de índices de cor para pixels acontece em PcxImageLoader.load; os demais formatos
		// passam pelo ImageIO. TextureLoader.loadTexture envia esses pixels para o OpenGL.
		BufferedImage image = loadTextureImage(texturePath);
		if (image == null) {
			throw new IOException("Formato de textura nao reconhecido: " + texturePath);
		}
		int textureId = TextureLoader.loadTexture(image);
		// Md2Model.load (em aula.md2.Md2Model) lê e decodifica o arquivo MD2 e cria
		// os VBOs. O try-with-resources garante que esses buffers serão liberados ao sair.
		try (Md2Model model = Md2Model.load(modelPath)) {
			// Os nomes são descobertos a partir dos nomes dos frames dentro do MD2;
			// buildAnimations, em Md2Model, agrupa os frames consecutivos em sequências.
			List<String> animationNames = model.getAnimationNames();
			System.out.println("MD2: " + modelPath + " | frames: " + model.getFrameCount()
					+ " | animacoes: " + animationNames);
			System.out.println("Textura: " + texturePath
					+ " | Controles: 1-9/SETAS selecionam animacao, ESPACO pausa, W wireframe, ESC sai.");

			glEnable(GL_TEXTURE_2D);
			glEnable(GL_LIGHTING);
			glEnable(GL_LIGHT0);
			glEnable(GL_COLOR_MATERIAL);
			glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);
			glLightModeli(GL_LIGHT_MODEL_TWO_SIDE, GL_TRUE);
			FloatBuffer lightPosition = BufferUtils.createFloatBuffer(4)
					.put(new float[] { -3, 5, 8, 1 });
			lightPosition.flip();
			glLightfv(GL_LIGHT0, GL_POSITION, lightPosition);
			glMaterialf(GL_FRONT_AND_BACK, GL_SHININESS, 24);
			glEnable(GL_NORMALIZE);
			glDisable(GL_CULL_FACE);

			// O loop trata entrada do teclado, avança a animação e desenha um quadro.
			// A janela continua responsiva porque glfwPollEvents é chamado a cada iteração.
			double previousTime = glfwGetTime();
			boolean paused = false;
			boolean previousSpaceDown = false;
			boolean previousLeftDown = false;
			boolean previousRightDown = false;
			boolean[] previousNumberDown = new boolean[9];
			while (!glfwWindowShouldClose(window)) {
				if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
					glfwSetWindowShouldClose(window, true);
				}
				boolean spaceDown = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
				if (spaceDown && !previousSpaceDown) {
					paused = !paused;
				}
				previousSpaceDown = spaceDown;
				for (int i = 0; i < previousNumberDown.length; i++) {
					boolean down = glfwGetKey(window, GLFW_KEY_1 + i) == GLFW_PRESS;
					if (down && !previousNumberDown[i] && i < animationNames.size()) {
						model.selectAnimation(i);
						System.out.println("Animacao: " + model.getCurrentAnimationName());
					}
					previousNumberDown[i] = down;
				}
				boolean leftDown = glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS;
				boolean rightDown = glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS;
				if (!animationNames.isEmpty() && leftDown && !previousLeftDown) {
					int previous = (animationNames.size() + animationNames.indexOf(
							model.getCurrentAnimationName()) - 1) % animationNames.size();
					model.selectAnimation(previous);
					System.out.println("Animacao: " + model.getCurrentAnimationName());
				} else if (!animationNames.isEmpty() && rightDown && !previousRightDown) {
					int next = (animationNames.indexOf(model.getCurrentAnimationName()) + 1)
							% animationNames.size();
					model.selectAnimation(next);
					System.out.println("Animacao: " + model.getCurrentAnimationName());
				}
				previousLeftDown = leftDown;
				previousRightDown = rightDown;

				double now = glfwGetTime();
				float elapsed = (float) Math.min(now - previousTime, 0.1);
				previousTime = now;
				if (!paused) {
					// Entrega o tempo decorrido ao modelo. Md2Model.update escolhe os dois
					// frames da sequência, interpola suas posições e atualiza os VBOs.
					model.update(elapsed);
				}

				int[] w = new int[1], h = new int[1];
				glfwGetFramebufferSize(window, w, h);
				if (h[0] == 0) {
					glfwPollEvents();
					continue;
				}
				glViewport(0, 0, w[0], h[0]);
				glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
				glMatrixMode(GL_PROJECTION);
				glLoadIdentity();
				double aspect = (double) w[0] / h[0];
				glFrustum(-aspect * .7, aspect * .7, -.7, .7, 1, 100);
				glMatrixMode(GL_MODELVIEW);
				glLoadIdentity();
				glTranslatef(0, 0, -4);
				glLightfv(GL_LIGHT0, GL_POSITION, lightPosition);
				glRotatef((float) (now * 18), 0, 1, 0);
				glScalef(model.getDisplayScale(), model.getDisplayScale(), model.getDisplayScale());
				glTranslatef(-model.getCenterX(), -model.getCenterY(), -model.getCenterZ());
				glPolygonMode(GL_FRONT_AND_BACK,
						glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS ? GL_LINE : GL_FILL);
				glColor3f(1, 1, 1);
				// A textura já foi enviada ao OpenGL e recebe este identificador.
				// model.draw configura os VBOs de posição, normal e UV e desenha os triângulos.
				glBindTexture(GL_TEXTURE_2D, textureId);
				model.draw();
				glfwSwapBuffers(window);
				glfwPollEvents();
			}
		} finally {
			glDeleteTextures(textureId);
		}
	}

	private static BufferedImage loadTextureImage(Path path) throws IOException {
		//Como a skin eh carregada, o loadTextureImage reconhece skins em .pcx e .png, 
		// e chama o TextureLoader para criar a textura OpenGL. 
		// A textura eh aplicada no draw do Md2Model.
		// Cada pixel guarda um indice de cor, o leitor decodifica o arquivo e cria um BufferedImage, que eh usado para criar a textura OpenGL.
		// Este método só decodifica a imagem para pixels em memória. A criação da textura
		// da GPU acontece depois, em TextureLoader.loadTexture, chamado por runMd2.
		if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pcx")) {
			return PcxImageLoader.load(path);
		}
		return ImageIO.read(path.toFile());
	}
}

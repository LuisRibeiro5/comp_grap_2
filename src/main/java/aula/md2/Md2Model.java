package aula.md2;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glDisableClientState;
import static org.lwjgl.opengl.GL11.glEnableClientState;
import static org.lwjgl.opengl.GL11.glNormalPointer;
import static org.lwjgl.opengl.GL11.glTexCoordPointer;
import static org.lwjgl.opengl.GL11.glVertexPointer;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.lwjgl.BufferUtils;

/**
 * Modelo Quake II MD2: carrega os frames comprimidos e interpola suas posições
 * no CPU antes de atualizar os VBOs usados pelo pipeline OpenGL de compatibilidade.
 */
public final class Md2Model implements AutoCloseable {
	private static final int HEADER_SIZE = 17 * Integer.BYTES;
	private static final int FRAME_HEADER_SIZE = 40;
	private static final int MD2_IDENT = 0x32504449; // "IDP2" em little-endian
	private static final int MD2_VERSION = 8;
	private static final float FRAMES_PER_SECOND = 10.0f;
	private static final Pattern TRAILING_FRAME_NUMBER = Pattern.compile("\\d+$");

	private final float[][] frames;
	private final String[] frameNames;
	private final int[] triangleIndices;
	private final float[] textureCoordinates;
	private final Map<String, Animation> animations;
	private final List<Animation> animationRanges;
	private final float centerX;
	private final float centerY;
	private final float centerZ;
	private final float displayScale;
	private final int positionVbo;
	private final int normalVbo;
	private final int textureVbo;
	private final FloatBuffer positionBuffer;
	private final FloatBuffer normalBuffer;
	private final int renderedVertexCount;
	private final float[] interpolatedPositions;
	private final float[] calculatedNormals;

	private int selectedAnimation;
	private float animationFrame;

	private Md2Model(ParsedMd2 parsed) {
		// Copia para o modelo os dados já decodificados por load. A partir daqui,
		// a classe organiza as sequências e prepara os buffers que serão usados no desenho.
		frames = parsed.frames;
		frameNames = parsed.frameNames;
		triangleIndices = parsed.triangleIndices;
		textureCoordinates = parsed.textureCoordinates;
		animations = buildAnimations(frameNames);
		animationRanges = List.copyOf(animations.values());

		// Calcula os limites espaciais de todos os frames. O centro posiciona o modelo
		// na origem e a maior dimensão determina uma escala automática para exibição.
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for (float[] frame : frames) {
			for (int vertex = 0; vertex < frame.length; vertex += 3) {
				minX = Math.min(minX, frame[vertex]);
				minY = Math.min(minY, frame[vertex + 1]);
				minZ = Math.min(minZ, frame[vertex + 2]);
				maxX = Math.max(maxX, frame[vertex]);
				maxY = Math.max(maxY, frame[vertex + 1]);
				maxZ = Math.max(maxZ, frame[vertex + 2]);
			}
		}
		centerX = (minX + maxX) * 0.5f;
		centerY = (minY + maxY) * 0.5f;
		centerZ = (minZ + maxZ) * 0.5f;
		float largestDimension = Math.max(maxX - minX,
				Math.max(maxY - minY, maxZ - minZ));
		displayScale = largestDimension > 0 ? 2.5f / largestDimension : 1.0f;

		// Para desenhar com GL_TRIANGLES, cada canto de triângulo será enviado ao VBO.
		// As posições/normais mudam com a animação; as UVs permanecem iguais em todos os frames.
		renderedVertexCount = triangleIndices.length;
		interpolatedPositions = new float[frames[0].length];
		calculatedNormals = new float[frames[0].length];
		positionBuffer = BufferUtils.createFloatBuffer(renderedVertexCount * 3);
		normalBuffer = BufferUtils.createFloatBuffer(renderedVertexCount * 3);
		// Prepara a pose inicial antes de criar os VBOs, para que o primeiro desenho
		// já tenha posições e normais válidas.
		updateGeometry(0, 0, 0);

		// VBOs dinâmicos recebem novos dados sempre que a pose muda.
		positionVbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
		glBufferData(GL_ARRAY_BUFFER, positionBuffer, GL_DYNAMIC_DRAW);

		normalVbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, normalVbo);
		glBufferData(GL_ARRAY_BUFFER, normalBuffer, GL_DYNAMIC_DRAW);

		// VBO estático: as coordenadas UV não mudam durante a animação, então são enviadas uma vez.
		textureVbo = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, textureVbo);
		FloatBuffer uvBuffer = BufferUtils.createFloatBuffer(textureCoordinates.length);
		uvBuffer.put(textureCoordinates).flip();
		glBufferData(GL_ARRAY_BUFFER, uvBuffer, GL_STATIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	public static Md2Model load(Path path) throws IOException {
		//faz a leitura do arquivo md2, verifica se o arquivo é valido 
		// e cria a malha interpolada para ser renderizada
		// Este é o ponto de entrada do carregamento MD2 chamado por Main.runMd2.
		// Ele decodifica o arquivo em dados Java e, no final, o construtor acima prepara os VBOs.
		byte[] file = Files.readAllBytes(path);
		if (file.length < HEADER_SIZE) {
			throw new IOException("Arquivo MD2 incompleto: cabecalho menor que 68 bytes.");
		}

		// O MD2 é binário e armazena números em little-endian; configurar a ordem
		// aqui faz getInt/getShort/getFloat interpretarem os bytes corretamente.
		ByteBuffer data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
		int ident = data.getInt();
		int version = data.getInt();
		if (ident != MD2_IDENT || version != MD2_VERSION) {
			throw new IOException("Formato MD2 invalido: esperado IDP2/version 8.");
		}

		// o metadado do arquvo fornece informações sobre o tamanho da textura,
		// quantidade de frames, vertices, triangulos, etc
		int skinWidth = data.getInt();
		int skinHeight = data.getInt();
		int frameSize = data.getInt();
		int skinCount = data.getInt();
		int vertexCount = data.getInt();
		int textureCoordinateCount = data.getInt();
		int triangleCount = data.getInt();
		int glCommandCount = data.getInt();
		int frameCount = data.getInt();
		int skinOffset = data.getInt();
		int textureCoordinateOffset = data.getInt();
		int triangleOffset = data.getInt();
		int frameOffset = data.getInt();
		int glCommandOffset = data.getInt();
		int endOffset = data.getInt();

		if (skinWidth <= 0 || skinHeight <= 0 || vertexCount <= 0
				|| textureCoordinateCount <= 0 || triangleCount <= 0 || frameCount <= 0) {
			throw new IOException("Cabecalho MD2 contem dimensoes ou contagens invalidas.");
		}
		if (frameSize < FRAME_HEADER_SIZE + (long) vertexCount * 4) {
			throw new IOException("Tamanho de frame MD2 inconsistente.");
		}
		checkSection(file.length, skinOffset, (long) skinCount * 64, "skins");
		checkSection(file.length, textureCoordinateOffset, (long) textureCoordinateCount * 4, "UVs");
		checkSection(file.length, triangleOffset, (long) triangleCount * 12, "triangulos");
		checkSection(file.length, frameOffset, (long) frameCount * frameSize, "frames");
		checkSection(file.length, glCommandOffset, (long) glCommandCount * 4, "comandos GL");
		if (endOffset < HEADER_SIZE || endOffset > file.length) {
			throw new IOException("Offset final do arquivo MD2 invalido.");
		}

		// Lê as UVs em uma tabela intermediária. Elas são coordenadas normalizadas
		// (entre 0 e 1) relativas à largura/altura informadas no cabeçalho.
		// Le as coordenadas uv da secao de textura e as expande para cada vertice do triangulo,
		float[] uvByIndex = new float[textureCoordinateCount * 2];
		data.position(textureCoordinateOffset);
		for (int i = 0; i < textureCoordinateCount; i++) {
			short s = data.getShort();
			short t = data.getShort();
			uvByIndex[i * 2] = (float) s / skinWidth;
			// TextureLoader envia a primeira linha da imagem como a primeira linha da textura.
			uvByIndex[i * 2 + 1] = (float) t / skinHeight;
		}

		// Expande a tabela UV para a ordem exata dos cantos dos triângulos. Isso é necessário
		// porque o MD2 mantém índices de posição e de textura separados.
		//le cada traingulo e cria um array de vertices e uvs para cada triangulo,
		// pois o MD2 referencia os vertices e coordenadas de textura separadamente.
		int[] triangleIndices = new int[triangleCount * 3];
		float[] expandedUvs = new float[triangleCount * 3 * 2];
		data.position(triangleOffset);
		for (int triangle = 0; triangle < triangleCount; triangle++) {
			int[] vertices = new int[3];
			int[] textureCoordinates = new int[3];
			for (int corner = 0; corner < 3; corner++) {
				vertices[corner] = Short.toUnsignedInt(data.getShort());
			}
			for (int corner = 0; corner < 3; corner++) {
				textureCoordinates[corner] = Short.toUnsignedInt(data.getShort());
			}
			for (int corner = 0; corner < 3; corner++) {
				if (vertices[corner] >= vertexCount
						|| textureCoordinates[corner] >= textureCoordinateCount) {
					throw new IOException("Triangulo MD2 referencia um indice inexistente.");
				}
				int expandedIndex = triangle * 3 + corner;
				triangleIndices[expandedIndex] = vertices[corner];
				expandedUvs[expandedIndex * 2] = uvByIndex[textureCoordinates[corner] * 2];
				expandedUvs[expandedIndex * 2 + 1] = uvByIndex[textureCoordinates[corner] * 2 + 1];
			}
		}

		// Cada frame descreve a mesma topologia em uma pose diferente. Aqui são lidos
		// os fatores de escala/translação e os bytes compactados de cada vértice.
		// le cada frame, descomprime os vertices e converte para a convenção OpenGL (Y para cima).
		float[][] frames = new float[frameCount][vertexCount * 3];
		String[] frameNames = new String[frameCount];
		for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
			int currentFrameOffset = frameOffset + frameIndex * frameSize;
			data.position(currentFrameOffset);
			float scaleX = data.getFloat();
			float scaleY = data.getFloat();
			float scaleZ = data.getFloat();
			float translateX = data.getFloat();
			float translateY = data.getFloat();
			float translateZ = data.getFloat();
			byte[] nameBytes = new byte[16];
			data.get(nameBytes);
			frameNames[frameIndex] = readFrameName(nameBytes);

			float[] positions = frames[frameIndex];
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				float x = Byte.toUnsignedInt(data.get());
				float y = Byte.toUnsignedInt(data.get());
				float z = Byte.toUnsignedInt(data.get());
				data.get(); // O índice de normal MD2 não é necessário para calcular a malha.

				// Conversão de coordenadas MD2 (Z para cima) para a convenção OpenGL (Y para cima).
				positions[vertex * 3] = translateX + x * scaleX;
				positions[vertex * 3 + 1] = translateZ + z * scaleZ;
				positions[vertex * 3 + 2] = -(translateY + y * scaleY);
			}
		}
		// Entrega os dados decodificados ao construtor Md2Model. Lá, buildAnimations agrupa
		// os frames pelo nome e são criados os buffers OpenGL para desenhar o modelo.
		// Cria o modelo MD2 com os frames descomprimidos, triangulos e coordenadas de textura.
		//como os frames viram animacoes? em buildAnimations
		return new Md2Model(new ParsedMd2(frames, frameNames, triangleIndices, expandedUvs));
	}

	public List<String> getAnimationNames() {
		return List.copyOf(animations.keySet());
	}

	public int getFrameCount() {
		return frames.length;
	}

	public String getCurrentAnimationName() {
		return getAnimationNames().get(selectedAnimation);
	}

	public void selectAnimation(int index) {
		if (index < 0 || index >= animations.size()) {
			throw new IndexOutOfBoundsException("Indice de animacao MD2 invalido: " + index);
		}
		selectedAnimation = index;
		animationFrame = 0;
		// Ao trocar de sequência, mostra imediatamente sua pose inicial no VBO,
		// sem esperar a próxima atualização do loop em Main.runMd2.
		updateGeometry(currentFrame(), nextFrame(), 0);
	}

	public void update(float elapsedSeconds) {
		// Atualiza o frame da animacao baseado no tempo decorrido, 
		// interpolando entre os frames atuais e seguintes, onde a interpolacao fica em
		// updateGeometry, que atualiza os VBOs com as posições e normais interpoladas.
		if (elapsedSeconds < 0 || !Float.isFinite(elapsedSeconds)) {
			throw new IllegalArgumentException("O tempo da animacao deve ser finito e nao negativo.");
		}
		// O contador é fracionário: sua parte inteira escolhe o frame e sua parte
		// decimal informa quanto interpolar até o próximo frame.
		Animation animation = currentAnimation();
		if (animation.frameCount() > 1) {
			animationFrame = (animationFrame + elapsedSeconds * FRAMES_PER_SECOND)
					% animation.frameCount();
		}
		updateGeometry(currentFrame(), nextFrame(), interpolationFactor());
	}

	public float getCenterX() {
		return centerX;
	}

	public float getCenterY() {
		return centerY;
	}

	public float getCenterZ() {
		return centerZ;
	}

	public float getDisplayScale() {
		return displayScale;
	}

	public void draw() {
		// Main.runMd2 já vinculou a textura. Aqui ligamos os arrays dos VBOs às
		// entradas fixas do OpenGL e emitimos os triângulos para a GPU renderizar.
		glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
		glVertexPointer(3, GL_FLOAT, 0, 0L);
		glBindBuffer(GL_ARRAY_BUFFER, normalVbo);
		glNormalPointer(GL_FLOAT, 0, 0L);
		glBindBuffer(GL_ARRAY_BUFFER, textureVbo);
		glTexCoordPointer(2, GL_FLOAT, 0, 0L);

		glEnableClientState(org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY);
		glEnableClientState(org.lwjgl.opengl.GL11.GL_NORMAL_ARRAY);
		glEnableClientState(org.lwjgl.opengl.GL11.GL_TEXTURE_COORD_ARRAY);
		glDrawArrays(GL_TRIANGLES, 0, renderedVertexCount);
		glDisableClientState(org.lwjgl.opengl.GL11.GL_TEXTURE_COORD_ARRAY);
		glDisableClientState(org.lwjgl.opengl.GL11.GL_NORMAL_ARRAY);
		glDisableClientState(org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	@Override
	public void close() {
		glDeleteBuffers(positionVbo);
		glDeleteBuffers(normalVbo);
		glDeleteBuffers(textureVbo);
	}

	private Animation currentAnimation() {
		return animationRanges.get(selectedAnimation);
	}

	private int currentFrame() {
		return currentAnimation().firstFrame + (int) animationFrame;
	}

	private int nextFrame() {
		Animation animation = currentAnimation();
		return animation.firstFrame + ((int) animationFrame + 1) % animation.frameCount();
	}

	private float interpolationFactor() {
		return animationFrame - (int) animationFrame;
	}

	private void updateGeometry(int firstFrame, int secondFrame, float amount) {
		// Faz a interpolação linear vértice a vértice. amount=0 mostra o primeiro
		// frame; amount=1 mostra o segundo; valores intermediários misturam as poses.
		float[] first = frames[firstFrame];
		float[] second = frames[secondFrame];
		for (int i = 0; i < first.length; i++) {
			interpolatedPositions[i] = first[i] + (second[i] - first[i]) * amount;
		}

		// As posições mudaram, então as normais antigas já não descrevem corretamente
		// a superfície. Recalcula-as a partir dos triângulos da pose interpolada.
		// Acumula normais por vértice para manter a superfície visualmente suave entre frames.
		float[] normals = calculatedNormals;
		java.util.Arrays.fill(normals, 0);
		for (int i = 0; i < triangleIndices.length; i += 3) {
			int a = triangleIndices[i] * 3;
			int b = triangleIndices[i + 1] * 3;
			int c = triangleIndices[i + 2] * 3;
			float abX = interpolatedPositions[b] - interpolatedPositions[a];
			float abY = interpolatedPositions[b + 1] - interpolatedPositions[a + 1];
			float abZ = interpolatedPositions[b + 2] - interpolatedPositions[a + 2];
			float acX = interpolatedPositions[c] - interpolatedPositions[a];
			float acY = interpolatedPositions[c + 1] - interpolatedPositions[a + 1];
			float acZ = interpolatedPositions[c + 2] - interpolatedPositions[a + 2];
			float normalX = abY * acZ - abZ * acY;
			float normalY = abZ * acX - abX * acZ;
			float normalZ = abX * acY - abY * acX;
			normals[a] += normalX;
			normals[a + 1] += normalY;
			normals[a + 2] += normalZ;
			normals[b] += normalX;
			normals[b + 1] += normalY;
			normals[b + 2] += normalZ;
			normals[c] += normalX;
			normals[c + 1] += normalY;
			normals[c + 2] += normalZ;
		}
		for (int i = 0; i < normals.length; i += 3) {
			float length = (float) Math.sqrt(normals[i] * normals[i]
					+ normals[i + 1] * normals[i + 1] + normals[i + 2] * normals[i + 2]);
			if (length > 0) {
				normals[i] /= length;
				normals[i + 1] /= length;
				normals[i + 2] /= length;
			}
		}

		// Os arrays internos guardam um vértice por posição original, mas o desenho
		// precisa dos dados expandidos na ordem dos triângulos. Copia essa ordem aos buffers.
		positionBuffer.clear();
		normalBuffer.clear();
		for (int index : triangleIndices) {
			int offset = index * 3;
			positionBuffer.put(interpolatedPositions, offset, 3);
			normalBuffer.put(normals, offset, 3);
		}
		positionBuffer.flip();
		normalBuffer.flip();

		// A chamada de updateGeometry também ocorre no construtor, antes da criação
		// dos VBOs. Nesse primeiro uso só prepara os buffers; nos usos seguintes
		// glBufferSubData envia a nova pose e as novas normais à GPU.
		if (positionVbo != 0) {
			glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
			glBufferSubData(GL_ARRAY_BUFFER, 0, positionBuffer);
			glBindBuffer(GL_ARRAY_BUFFER, normalVbo);
			glBufferSubData(GL_ARRAY_BUFFER, 0, normalBuffer);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
		}
	}

	private static Map<String, Animation> buildAnimations(String[] frameNames) {
		//agrupa os frames em animacoes, cada animacao tem um nome e um range de frames
		// o avanco das animacoes acontece em .update
		// Exemplo: nomes walk01, walk02 e walk03 viram uma sequência "walk"
		// com o frame inicial e a quantidade de poses consecutivas.
		Map<String, Animation> animations = new LinkedHashMap<>();
		for (int frame = 0; frame < frameNames.length; frame++) {
			String name = TRAILING_FRAME_NUMBER.matcher(frameNames[frame]).replaceFirst("");
			if (name.isBlank()) {
				name = "frames";
			}
			Animation existing = animations.get(name);
			if (existing == null) {
				animations.put(name, new Animation(frame, 1));
			} else if (existing.firstFrame + existing.frameCount == frame) {
				animations.put(name, new Animation(existing.firstFrame, existing.frameCount + 1));
			} else {
				// Arquivos MD2 organizam cada sequência em blocos contínuos; nomes repetidos
				// fora de ordem ganham um identificador próprio para não saltar frames.
				animations.put(name + "_" + frame, new Animation(frame, 1));
			}
		}
		return animations;
	}

	private static String readFrameName(byte[] bytes) {
		int length = 0;
		while (length < bytes.length && bytes[length] != 0) {
			length++;
		}
		return new String(bytes, 0, length, StandardCharsets.US_ASCII);
	}

	private static void checkSection(int fileLength, int offset, long length, String section)
			throws IOException {
		if (offset < HEADER_SIZE || length < 0 || (long) offset + length > fileLength) {
			throw new IOException("Secao " + section + " fora dos limites do arquivo MD2.");
		}
	}

	private record Animation(int firstFrame, int frameCount) {
	}

	private record ParsedMd2(float[][] frames, String[] frameNames, int[] triangleIndices,
			float[] textureCoordinates) {
	}
}

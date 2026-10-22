package aula.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Leitor mínimo para skins PCX indexadas de 8 bits, comuns em modelos Quake II. */
public final class PcxImageLoader {
	private static final int HEADER_SIZE = 128;
	private static final int PALETTE_SIZE = 769;
	private static final int MAX_PIXELS = 100_000_000;

	private PcxImageLoader() {
	}

	public static BufferedImage load(Path path) throws IOException {
		// PCX não é aberto pelo ImageIO nesta aplicação. Este leitor transforma o
		// arquivo indexado em BufferedImage; Main depois passa essa imagem a
		// TextureLoader.loadTexture para criar a textura OpenGL.
		byte[] file = Files.readAllBytes(path);
		if (file.length < HEADER_SIZE + PALETTE_SIZE
				|| unsigned(file[0]) != 10 || unsigned(file[2]) != 1
				|| unsigned(file[3]) != 8 || unsigned(file[65]) != 1) {
			throw new IOException("PCX nao suportado: esperado imagem indexada de 8 bits/1 plano.");
		}

		// O cabeçalho guarda os limites da imagem e os bytes por linha. A paleta de
		// 256 cores fica no final do arquivo, precedida pelo marcador 12.
		int width = littleEndianShort(file, 8) - littleEndianShort(file, 4) + 1;
		int height = littleEndianShort(file, 10) - littleEndianShort(file, 6) + 1;
		int bytesPerLine = littleEndianShort(file, 66);
		long pixelCount = (long) width * height;
		long decodedSize = (long) bytesPerLine * height;
		int paletteOffset = file.length - PALETTE_SIZE;
		if (width <= 0 || height <= 0 || bytesPerLine < width || pixelCount > MAX_PIXELS
				|| decodedSize > MAX_PIXELS || unsigned(file[paletteOffset]) != 12) {
			throw new IOException("Cabecalho ou paleta PCX invalido: " + path);
		}

		// Decodifica a compressão RLE do PCX para recuperar um índice de paleta por pixel.
		// Cada sequência comprimida representa um valor repetido (ou um único valor).
		byte[] indices = new byte[(int) decodedSize];
		int source = HEADER_SIZE;
		int target = 0;
		while (target < indices.length && source < paletteOffset) {
			int value = unsigned(file[source++]);
			int runLength = 1;
			if ((value & 0xC0) == 0xC0) {
				runLength = value & 0x3F;
				if (runLength == 0 || source >= paletteOffset) {
					throw new IOException("Dados RLE truncados no arquivo PCX: " + path);
				}
				value = unsigned(file[source++]);
			}
			if (target + runLength > indices.length) {
				throw new IOException("Dados RLE excedem as dimensoes da imagem PCX: " + path);
			}
			for (int i = 0; i < runLength; i++) {
				indices[target++] = (byte) value;
			}
		}
		if (target != indices.length) {
			throw new IOException("Dados RLE incompletos no arquivo PCX: " + path);
		}

		// Troca cada índice de cor pelo RGB correspondente na paleta e monta a imagem
		// convencional que será recebida por TextureLoader.loadTexture.
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int paletteIndex = unsigned(indices[y * bytesPerLine + x]);
				int palette = paletteOffset + 1 + paletteIndex * 3;
				int rgb = 0xFF000000 | unsigned(file[palette]) << 16
						| unsigned(file[palette + 1]) << 8 | unsigned(file[palette + 2]);
				image.setRGB(x, y, rgb);
			}
		}
		return image;
	}

	private static int littleEndianShort(byte[] bytes, int offset) {
		return unsigned(bytes[offset]) | unsigned(bytes[offset + 1]) << 8;
	}

	private static int unsigned(byte value) {
		return Byte.toUnsignedInt(value);
	}
}

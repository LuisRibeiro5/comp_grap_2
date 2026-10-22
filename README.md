# Aula: VBO e OBJ com LWJGL 3 e Maven

Requisitos: JDK 17+, Maven 3.9+ e driver com suporte a OpenGL 2.1 (compatibility profile).

## Executar

Na raiz do projeto:

```bash
mvn compile exec:java
```

Para carregar outro arquivo OBJ:

```bash
mvn compile exec:java -Dexec.args="caminho/para/modelo.obj"
```

Para carregar um modelo MD2 (Quake II), informe também a imagem de textura usada pelo modelo:

```bash
mvn compile exec:java -Dexec.args="caminho/para/modelo.md2 caminho/para/textura.png"
```

No arquivo `banana.zip` do pacote Quake II, use `TRIS.MD2` com sua skin `Bip.pcx`. Extraia o ZIP
para uma pasta `banana/` na raiz do projeto e execute:

```bash
mvn compile exec:java -Dexec.args="banana/TRIS.MD2 banana/Bip.pcx"
```

O MD2 armazena os vértices de cada pose (frame), as coordenadas UV e os triângulos, mas a imagem
da textura é um arquivo separado. Use a skin correspondente ao modelo; o leitor aceita imagens
suportadas pelo `ImageIO`, como PNG, JPG e BMP, além de skins PCX indexadas de 8 bits (formato
comum em modelos Quake II). O programa agrupa os frames consecutivos pelo nome (por exemplo,
`run01`, `run02`, ...) para obter as animações disponíveis.

No Windows, também é possível abrir o projeto como **Maven Project** no IntelliJ ou Eclipse e executar `aula.Main`.

No macOS, a janela GLFW pode exigir execução com `-XstartOnFirstThread`. Se necessário, rode pela IDE com essa opção na JVM (o `exec:java` não cria outra JVM para aplicar esse argumento).

Se o terminal do VS Code instalado via Snap encerrar o programa com um erro GLib dizendo que o
schema `org.gnome.settings-daemon.plugins.xsettings` não possui a chave `antialiasing`, execute
com o diretório de schemas do sistema:

```bash
GSETTINGS_SCHEMA_DIR=/usr/share/glib-2.0/schemas mvn compile exec:java -Dexec.args="banana/TRIS.MD2 banana/Bip.pcx"
```

## Controles

- Segure `W` para wireframe.
- No modo MD2, pressione `1` a `9` para selecionar uma das animações listadas no terminal; as
  setas esquerda/direita percorrem todas as sequências.
- No modo MD2, pressione `ESPAÇO` para pausar/retomar a animação.
- `ESC` fecha a janela.

## Como explicar a animação MD2 na defesa

- O arquivo começa com um cabeçalho binário `IDP2`, versão 8, que informa contagens e offsets.
- Cada frame guarda uma transformação (escala e translação) e vértices compactados em bytes. O
  leitor decodifica as posições e converte o eixo vertical do formato MD2 para o eixo Y do OpenGL.
- Cada triângulo referencia índices de vértice e de coordenada de textura. As UVs são expandidas por
  canto do triângulo, pois uma mesma posição pode ter UVs diferentes em uma costura da textura.
- A animação percorre a sequência de frames a 10 frames por segundo. Entre dois frames, cada posição
  é calculada por `P = P1 + (P2 - P1) * t`, onde `t` varia de 0 a 1. Assim, não há saltos visíveis
  entre as poses armazenadas.
- As posições interpoladas e as normais calculadas da malha atualizam VBOs dinâmicos; UVs ficam em
  um VBO estático. A textura é carregada separadamente e aplicada pelo pipeline OpenGL.

## Objetivos didáticos

O código usa três VBOs (`positions`, `normals`, `uvs`) e renderiza com o pipeline de compatibilidade, sem shaders e sem VAO. O próximo passo é migrar para VAO, `glVertexAttribPointer` e GLSL.

O parser OBJ suporta `v`, `vt`, `vn`, `f`, índices positivos/negativos e triangulação em leque (para faces convexas). Não carrega `.mtl`, imagens de textura ou smoothing groups. Normais ausentes recebem valor padrão. Faces côncavas precisam ser trianguladas previamente. O VBO de UVs existe, mas não há texturização nesta aula.

Perfis Maven para natives: Windows x64/ARM64, Linux x64/ARM64 e macOS Intel/Apple Silicon.

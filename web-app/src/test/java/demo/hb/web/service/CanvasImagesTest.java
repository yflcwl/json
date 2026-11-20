package demo.hb.web.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import demo.hb.web.repository.CanvasRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CanvasImagesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    @TempDir Path directory;
    private CanvasRepository canvases;
    private CanvasImages images;
    private UUID id;

    @BeforeEach
    void setUp() throws Exception {
        var properties = new SketchProperties(null, new SketchProperties.Generation(524288),
                directory.toString(), "http://localhost");
        canvases = new CanvasRepository(properties);
        images = new CanvasImages(properties, canvases);
        id = UUID.randomUUID();
        canvases.write(id, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("formats")
    void storesAndResolvesSupportedFormats(byte[] bytes, String extension) throws Exception {
        String reference = images.store(id, bytes);
        assertTrue(reference.matches("uploads/[a-f0-9]{32}\\." + extension));
        List<String> paths = images.resolve(id, List.of(reference));
        assertEquals(1, paths.size());
        assertEquals(directory.resolve(id.toString()).resolve(reference).toString(), paths.get(0));
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(paths.get(0))));
    }

    static Stream<Arguments> formats() {
        return Stream.of(Arguments.of(PNG, "png"),
                Arguments.of(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "jpg"),
                Arguments.of("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "gif"),
                Arguments.of("RIFF0000WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "webp"));
    }

    @Test
    void rejectsEmptyOversizedAndUnsupportedContent() {
        for (byte[] content : Arrays.asList(null, new byte[0], new byte[]{1, 2, 3})) {
            assertEquals(Code.INVALID_INPUT,
                    assertThrows(GenerationException.class, () -> images.store(id, content)).code());
        }
        assertEquals(Code.REQUEST_TOO_LARGE, assertThrows(GenerationException.class,
                () -> images.store(id, new byte[5 * 1024 * 1024 + 1])).code());
        assertEquals(Code.NOT_FOUND, assertThrows(GenerationException.class,
                () -> images.store(UUID.randomUUID(), PNG)).code());
    }

    @Test
    void rejectsMissingAbsoluteAndEscapingReferences() throws Exception {
        String valid = images.store(id, PNG);
        Path outside = directory.resolve("outside.png");
        Files.write(outside, PNG);
        for (String reference : Arrays.asList(null, " ", "x".repeat(201), "uploads/missing.png",
                outside.toString(), "../outside.png", "uploads/../../outside.png")) {
            assertEquals(Code.INVALID_INPUT, assertThrows(GenerationException.class,
                    () -> images.resolve(id, Arrays.asList(reference))).code());
        }
        Path stored = Path.of(images.resolve(id, List.of(valid)).get(0));
        Files.write(stored, new byte[]{0});
        assertEquals(Code.INVALID_INPUT, assertThrows(GenerationException.class,
                () -> images.resolve(id, List.of(valid))).code());
        Files.write(stored, Arrays.copyOf(PNG, 5 * 1024 * 1024 + 1));
        assertEquals(Code.REQUEST_TOO_LARGE, assertThrows(GenerationException.class,
                () -> images.resolve(id, List.of(valid))).code());
        assertEquals(List.of(), images.resolve(id, List.of()));
    }

    @Test
    void deletingCanvasAlsoRemovesItsImages() {
        String reference = images.store(id, PNG);
        canvases.delete(id);
        assertEquals(Code.INVALID_INPUT, assertThrows(GenerationException.class,
                () -> images.resolve(id, List.of(reference))).code());
    }
}

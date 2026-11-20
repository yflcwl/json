package demo.hb.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import demo.hb.web.repository.CanvasRepository;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class CanvasServiceTest {

    private static final String EMPTY = "{\"version\":1,\"canvasSize\":\"960x600\",\"components\":[]}";
    private static final String COMPONENT = """
            {"id":"title","type":"text","x":10,"y":20,"width":100,"height":30,
             "content":"标题","animation":{"trigger":"click","effect":"none"},
             "timeline":{"effect":"scale","start":100,"duration":200},
             "textColor":"#aAbB09","backgroundColor":"#FFFFFF"}
            """;

    @TempDir Path directory;
    private CanvasService canvases;
    private UUID id;

    @BeforeEach
    void setUp() throws Exception {
        var properties = new SketchProperties(null, new SketchProperties.Generation(524288),
                directory.toString(), "http://localhost");
        var repository = new CanvasRepository(properties);
        canvases = new CanvasService(repository, null, new CanvasPageValidator(new JsonMapper(), properties), properties,
                new CanvasImages(properties, repository));
        id = UUID.fromString(canvases.create());
    }

    @Test
    void persistsValidPagesWithoutReformatting() {
        String page = page(COMPONENT);
        canvases.savePage(id, page);
        assertEquals(page, canvases.page(id));
        assertDoesNotThrow(() -> canvases.savePage(id, EMPTY));
        assertDoesNotThrow(() -> canvases.savePage(id, page(COMPONENT.replace("\"text\"", "\"divider\"")
                .replace("\"height\":30", "\"height\":12"))));
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void rejectsInvalidPagesWithoutOverwritingSavedData(String page, Code code, String message) {
        GenerationException failure = assertThrows(GenerationException.class, () -> canvases.savePage(id, page));
        assertEquals(code, failure.code());
        assertEquals(message, failure.getMessage());
        assertEquals(EMPTY, canvases.page(id));
    }

    static Stream<Arguments> invalidPages() {
        return Stream.of(
                Arguments.of(null, Code.REQUEST_TOO_LARGE, "画布数据超过大小限制。"),
                Arguments.of("中".repeat(174763), Code.REQUEST_TOO_LARGE, "画布数据超过大小限制。"),
                Arguments.of("{", Code.INVALID_PAGE, "画布数据不是有效的 JSON。"),
                Arguments.of("{}", Code.INVALID_PAGE, "画布数据结构无效。"),
                Arguments.of(EMPTY.replace("960x600", "1x1"), Code.INVALID_PAGE, "画布尺寸无效。"),
                Arguments.of(page(COMPONENT + "," + COMPONENT), Code.INVALID_PAGE, "组件 ID 缺失或重复。"),
                Arguments.of(page(COMPONENT.replace("\"text\"", "\"unknown\"")), Code.INVALID_PAGE, "组件类型无效。"),
                Arguments.of(page(COMPONENT.replace("\"x\":10", "\"x\":900")), Code.INVALID_PAGE, "组件坐标或尺寸超出画布范围。"),
                Arguments.of(page(COMPONENT.replace("\"width\":100", "\"width\":39")), Code.INVALID_PAGE, "组件坐标或尺寸超出画布范围。"),
                Arguments.of(page(COMPONENT.replace("标题", "字".repeat(201))), Code.INVALID_PAGE, "组件文字超过长度限制。"),
                Arguments.of(page(COMPONENT.replace("\"text\"", "\"card\"")
                        .replace("\"content\":", "\"description\":\"" + "字".repeat(501) + "\",\"content\":")),
                        Code.INVALID_PAGE, "卡片描述超过长度限制。"),
                Arguments.of(page(COMPONENT.replace("\"click\"", "\"unknown\"")), Code.INVALID_PAGE, "组件动画配置无效。"),
                Arguments.of(page(COMPONENT.replace("\"start\":100", "\"start\":9900")), Code.INVALID_PAGE, "组件时间轴配置无效。"),
                Arguments.of(page(COMPONENT.replace("\"duration\":200", "\"duration\":99")), Code.INVALID_PAGE, "组件时间轴配置无效。"),
                Arguments.of(page(COMPONENT.replace("#aAbB09", "red")), Code.INVALID_PAGE, "组件颜色格式无效。"));
    }

    private static String page(String components) {
        return "{\"version\":1,\"canvasSize\":\"960x600\",\"components\":[" + components + "]}";
    }
}

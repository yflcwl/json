package demo.hb.web.service;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class CanvasPageValidator {

    private static final int MAX_COMPONENTS = 200;
    private static final int TIMELINE_DURATION = 10000;
    private static final Map<String, int[]> CANVAS_DIMENSIONS = Map.of(
            "960x600", new int[]{960, 600}, "1280x720", new int[]{1280, 720},
            "1920x1080", new int[]{1920, 1080}, "390x844", new int[]{390, 844});
    private static final Set<String> COMPONENT_TYPES = Set.of("button", "text", "card", "heading", "tag", "divider");
    private static final Set<String> TRIGGERS = Set.of("click", "hover");
    private static final Set<String> EFFECTS = Set.of("none", "shadow", "scale", "rotate");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private final JsonMapper json;
    private final int maxPageBytes;

    public CanvasPageValidator(JsonMapper json, SketchProperties properties) {
        this.json = json;
        this.maxPageBytes = properties.generation().maxPageBytes();
    }

    public void validate(String page) {
        if (page == null || page.getBytes(StandardCharsets.UTF_8).length > maxPageBytes) {
            throw new GenerationException(Code.REQUEST_TOO_LARGE, "画布数据超过大小限制。");
        }
        JsonNode root;
        try {
            root = json.readTree(page);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new GenerationException(Code.INVALID_PAGE, "画布数据不是有效的 JSON。");
        }
        if (root == null || !root.isObject() || root.path("version").asInt(-1) != 1
                || !root.path("components").isArray()) {
            throw new GenerationException(Code.INVALID_PAGE, "画布数据结构无效。");
        }
        int[] dimensions = CANVAS_DIMENSIONS.get(root.path("canvasSize").asText(null));
        if (dimensions == null) {
            throw new GenerationException(Code.INVALID_PAGE, "画布尺寸无效。");
        }
        JsonNode components = root.path("components");
        if (components.size() > MAX_COMPONENTS) {
            throw new GenerationException(Code.INVALID_PAGE, "画布组件数量超限。");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode item : components) {
            if (!item.isObject() || !item.path("id").isTextual() || item.path("id").asText().isBlank()
                    || !ids.add(item.path("id").asText())) {
                throw new GenerationException(Code.INVALID_PAGE, "组件 ID 缺失或重复。");
            }
            String type = item.path("type").asText(null);
            if (!COMPONENT_TYPES.contains(type)) {
                throw new GenerationException(Code.INVALID_PAGE, "组件类型无效。");
            }
            long x = item.path("x").asLong(-1);
            long y = item.path("y").asLong(-1);
            long width = item.path("width").asLong(-1);
            long height = item.path("height").asLong(-1);
            if (x < 0 || y < 0 || width < 40 || height < ("divider".equals(type) ? 12 : 24)
                    || x + width > dimensions[0] || y + height > dimensions[1]) {
                throw new GenerationException(Code.INVALID_PAGE, "组件坐标或尺寸超出画布范围。");
            }
            if (item.path("content").asText("").length() > 200) {
                throw new GenerationException(Code.INVALID_PAGE, "组件文字超过长度限制。");
            }
            if ("card".equals(type) && item.path("description").asText("").length() > 500) {
                throw new GenerationException(Code.INVALID_PAGE, "卡片描述超过长度限制。");
            }
            JsonNode animation = item.path("animation");
            if (!animation.isObject() || !TRIGGERS.contains(animation.path("trigger").asText(null))
                    || !EFFECTS.contains(animation.path("effect").asText(null))) {
                throw new GenerationException(Code.INVALID_PAGE, "组件动画配置无效。");
            }
            JsonNode timeline = item.path("timeline");
            if (!timeline.isMissingNode() && !timeline.isNull()) {
                if (!timeline.isObject()) {
                    throw new GenerationException(Code.INVALID_PAGE, "组件时间轴配置无效。");
                }
                long start = timeline.path("start").asLong(-1);
                long duration = timeline.path("duration").asLong(-1);
                if (!EFFECTS.contains(timeline.path("effect").asText(null))
                        || start < 0 || duration < 100 || start + duration > TIMELINE_DURATION) {
                    throw new GenerationException(Code.INVALID_PAGE, "组件时间轴配置无效。");
                }
            }
            for (String property : List.of("textColor", "backgroundColor")) {
                if (item.has(property) && !HEX_COLOR.matcher(item.path(property).asText("")).matches()) {
                    throw new GenerationException(Code.INVALID_PAGE, "组件颜色格式无效。");
                }
            }
        }
    }
}

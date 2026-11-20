package demo.hb.web.controller;

import demo.hb.web.config.SketchProperties;
import demo.hb.web.dto.GenerateInput;
import demo.hb.web.exception.GenerationException;
import demo.hb.web.exception.GenerationException.Code;
import demo.hb.web.repository.CanvasRepository;
import demo.hb.web.service.CanvasService;
import demo.hb.web.service.CanvasImages;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/canvases")
public class CanvasController {

    private final CanvasService service;
    private final CanvasImages images;
    private final JsonMapper json;
    private final int pageRequestLimit;

    public CanvasController(CanvasService service, JsonMapper json, SketchProperties properties,
                            CanvasImages images) {
        this.service = service;
        this.images = images;
        this.json = json;
        pageRequestLimit = 6 * properties.generation().maxPageBytes() + 1024;
    }

    @PostMapping
    public Map<String, String> create() {
        return Map.of("id", service.create());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<CanvasRepository.CanvasInfo> list() {
        return service.list();
    }

    @GetMapping(path = "/{id}/page", produces = MediaType.APPLICATION_JSON_VALUE)
    public String page(@PathVariable("id") UUID id) {
        return service.page(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") UUID id) {
        service.delete(id);
    }

    @PutMapping(path = "/{id}/page", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void savePage(@PathVariable("id") UUID id, HttpServletRequest request) throws IOException {
        service.savePage(id, readText(request, pageRequestLimit));
    }

    @PostMapping(path = "/{id}/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void generate(@PathVariable("id") UUID id, HttpServletRequest request) throws IOException {
        GenerateInput input = read(request, GenerateInput.class, 8192);
        service.generate(id, input);
    }

    @PostMapping(path = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> uploadImage(@PathVariable("id") UUID id,
                                           @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new GenerationException(Code.STORAGE_FAILURE, "图片读取失败，请重试。");
        }
        return Map.of("file", images.store(id, content));
    }

    @GetMapping(path = "/{id}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode history(@PathVariable("id") UUID id) {
        return service.history(id);
    }

    @GetMapping(path = "/{id}/rounds", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode rounds(@PathVariable("id") UUID id) {
        return service.rounds(id);
    }

    @GetMapping(path = "/{id}/rounds/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode round(@PathVariable("id") UUID id, @PathVariable("runId") String runId) {
        return service.round(id, runId);
    }

    private <T> T read(HttpServletRequest request, Class<T> type, int limit) throws IOException {
        byte[] body = readBody(request, limit);
        try {
            T value = json.readValue(body, type);
            if (value != null) {
                return value;
            }
        } catch (JacksonException ex) {
            throw new GenerationException(Code.INVALID_INPUT, "请求数据格式无效。");
        }
        throw new GenerationException(Code.INVALID_INPUT, "请求数据为空。");
    }

    private static String readText(HttpServletRequest request, int limit) throws IOException {
        return new String(readBody(request, limit), StandardCharsets.UTF_8);
    }

    private static byte[] readBody(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) {
            throw new GenerationException(Code.REQUEST_TOO_LARGE, "请求超过大小限制。");
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            throw new GenerationException(Code.REQUEST_TOO_LARGE, "请求超过大小限制。");
        }
        return body;
    }
}


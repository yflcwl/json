package demo.hb.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import demo.hb.web.config.SketchProperties;
import demo.hb.web.service.AgentDispatchClient.AgentFailure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class AgentDispatchClientTest {

    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void close() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsExistingPayloadAndCompletesOnSuccess() throws Exception {
        var client = client(200, "", false);
        UUID id = UUID.randomUUID();
        assertDoesNotThrow(() -> client.generate(id, "move", "{}", "http://localhost", "canvas-skill", List.of("image.png")));
        var payload = new JsonMapper().readTree(body.get());
        assertEquals(id.toString(), payload.path("canvasId").asText());
        assertEquals("move", payload.path("message").asText());
        assertEquals("{}", payload.path("page").asText());
        assertEquals("http://localhost", payload.path("uiUrl").asText());
        assertEquals("canvas-skill", payload.path("skillId").asText());
        assertEquals("image.png", payload.path("images").get(0).asText());
        assertEquals(1, received.get());
    }

    @ParameterizedTest
    @MethodSource("failedResponses")
    void doesNotRepeatFailedOrInterruptedGeneration(int status, String response, boolean interrupted,
                                                   String expectedCode) throws Exception {
        var client = client(status, response, interrupted);
        var failure = assertThrows(AgentFailure.class, () -> client.generate(UUID.randomUUID(),
                "move", "{}", "http://localhost", null, List.of()));
        assertEquals(expectedCode, failure.getMessage());
        assertEquals(1, received.get(), "一次生成不得被自动重复执行");
    }

    static Stream<Arguments> failedResponses() {
        return Stream.of(
                Arguments.of(503, "", false, "MODEL_FAILURE"),
                Arguments.of(502, "{\"error\":\"GENERATION_TIMEOUT\"}", false, "GENERATION_TIMEOUT"),
                Arguments.of(400, "{\"error\":\"INVALID_INPUT\"}", false, "INVALID_INPUT"),
                Arguments.of(200, "", true, "AGENT_UNAVAILABLE"),
                Arguments.of(0, "", true, "AGENT_UNAVAILABLE"));
    }

    private AgentDispatchClient client(int status, String response, boolean interrupted) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runs", exchange -> {
            try (exchange) {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                received.incrementAndGet();
                if (interrupted) {
                    if (status != 0) {
                        exchange.sendResponseHeaders(status, 100);
                        exchange.getResponseBody().write('x');
                    }
                    return;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length != 0) exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        var settings = new SketchProperties.Agent(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new AgentDispatchClient(new SketchProperties(settings, null, null, null), new JsonMapper());
    }
}

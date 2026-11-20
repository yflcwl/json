package demo.hb.web.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sketch")
public record SketchProperties(Agent agent, Generation generation, String dataDirectory, String uiUrl) {

    public record Agent(URI url, Duration connectTimeout, Duration requestTimeout) {
    }

    public record Generation(int maxPageBytes) {
    }
}

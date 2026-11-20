package demo.hb.agent.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties
public record AgentSettings(String apiKey, String model, String baseUrl, Boolean enableThinking,
                            int timeoutSeconds, @DefaultValue("1048576") int maxDispatchBytes,
                            String systemPrompt, String userPrompt,
                            @DefaultValue("http://127.0.0.1:8086") String uiUrl,
                            Server server, Compaction compaction) {

    public record Compaction(boolean enabled, int keepMessages, int triggerMessages, int triggerTokens) {
        public Compaction {
            if (keepMessages < 2 || triggerMessages < 0 || triggerTokens < 0) {
                throw new IllegalArgumentException("Compaction requires keep-messages >= 2 and non-negative trigger-messages / trigger-tokens");
            }
        }
    }

    public record Server(String address, int port) {
        public Server {
            if (address == null || address.isBlank() || port < 0 || port > 65535) {
                throw new IllegalArgumentException("Agent requires server.address and server.port between 0 and 65535");
            }
        }
    }

    public AgentSettings {
        if (model == null || model.isBlank() || systemPrompt == null || systemPrompt.isBlank()
                || userPrompt == null || userPrompt.isBlank()
                || timeoutSeconds < 1 || maxDispatchBytes < 1 || maxDispatchBytes == Integer.MAX_VALUE
                || server == null || compaction == null) {
            throw new IllegalArgumentException("Agent YAML requires model, prompts and positive limits");
        }
    }
}

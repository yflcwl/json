package demo.hb.agent.config;

import demo.hb.agent.service.HtmlGenerationService;
import demo.hb.agent.session.SessionRepository;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentSettings.class)
public class AgentConfiguration {

    @Bean(destroyMethod = "close")
    AgentSkillRepository agentSkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills");
    }

    @Bean(destroyMethod = "close")
    HtmlGenerationService htmlGenerationService(AgentSettings settings, AgentSkillRepository skillRepository,
                                                SessionRepository sessions) {
        if (!StandardCharsets.UTF_8.equals(Charset.defaultCharset())) {
            throw new IllegalStateException("Start the Agent JVM with -Dfile.encoding=UTF-8");
        }
        return new HtmlGenerationService(settings, HtmlAgentFactory.create(settings, skillRepository, sessions));
    }

    @Bean
    SessionRepository sessionRepository() {
        return new SessionRepository(Path.of("agent-state"));
    }

    @Bean
    FilterRegistrationBean<Filter> agentLocalRequests(AgentSettings settings) throws IOException {
        var address = InetAddress.getByName(settings.server().address());
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("Agent server.address must resolve to a loopback address");
        }
        var hosts = new HashSet<>(List.of("localhost", settings.server().address().toLowerCase(Locale.ROOT),
                address.getHostAddress(), "[" + address.getHostAddress() + "]"));
        if (address.getAddress().length == 16) {
            hosts.addAll(List.of("::1", "[::1]"));
        }
        Filter filter = (request, response, chain) -> {
            var http = (HttpServletRequest) request;
            if (http.getHeader("Origin") != null || http.getServerPort() != http.getLocalPort()
                    || !hosts.contains(http.getServerName().toLowerCase(Locale.ROOT))) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            chain.doFilter(request, response);
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/internal/runs", "/internal/runs/*",
                "/internal/sessions", "/internal/sessions/*", "/internal/skills");
        return registration;
    }
}

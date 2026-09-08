package com.se.frms.gateway.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "frms.security")
public class GatewaySecurityProperties {

    /**
     * Ant-style path patterns (e.g. /actuator/**) that bypass JWT
     * validation entirely. Everything else requires a valid Bearer token.
     */
    private List<String> publicPaths = List.of();

    private Jwt jwt = new Jwt();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
    }
}

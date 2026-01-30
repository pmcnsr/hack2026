package com.openai.hackathon;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "local")
public record LocalAIProps(
        String token,
        String baseUrl,
        String model
) {

}


package com.openai.hackathon;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAIProps(
        String apiKey,
        String baseUrl,
        String model,
        String vectorStoreId
) {}

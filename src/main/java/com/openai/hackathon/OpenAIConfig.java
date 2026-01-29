package com.openai.hackathon;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(OpenAIProps.class)
public class OpenAIConfig {

    @Bean
    WebClient openAiWebClient(OpenAIProps props) {

        System.out.println("OpenAIProps - model: " + props.model());

        return WebClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
    }
}

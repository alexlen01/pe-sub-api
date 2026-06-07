package com.ubs.pesubapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ExtractionClientConfig {

    @Value("${pe-sub-extraction.base-url:http://localhost:3002}")
    private String extractionBaseUrl;

    @Bean
    public RestClient peSubExtractionClient() {
        String url = extractionBaseUrl != null ? extractionBaseUrl : "http://localhost:3002";
        return RestClient.builder()
            .baseUrl(url)
            .build();
    }
}

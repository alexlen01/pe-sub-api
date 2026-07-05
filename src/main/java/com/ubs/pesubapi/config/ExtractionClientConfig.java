package com.ubs.pesubapi.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ExtractionClientConfig {

    // Without explicit timeouts a hung pe-sub-extraction pins the calling thread forever;
    // reads stay generous because large-workbook extraction is legitimately slow.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT    = Duration.ofSeconds(120);

    @Value("${pe-sub-extraction.base-url:http://localhost:3002}")
    private String extractionBaseUrl;

    @Bean
    public RestClient peSubExtractionClient() {
        String url = extractionBaseUrl != null ? extractionBaseUrl : "http://localhost:3002";
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
            .baseUrl(url)
            .requestFactory(requestFactory)
            .requestInterceptor((request, body, execution) -> {
                String txId = MDC.get(TransactionLoggingFilter.MDC_KEY);
                if (txId != null && !txId.isBlank()) {
                    request.getHeaders().set(TransactionLoggingFilter.HEADER, txId);
                }
                return execution.execute(request, body);
            })
            .build();
    }
}

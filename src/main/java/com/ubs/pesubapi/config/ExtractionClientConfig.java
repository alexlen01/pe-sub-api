package com.ubs.pesubapi.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

@Configuration
public class ExtractionClientConfig {

    // Without explicit timeouts a hung pe-sub-extraction pins the calling thread forever;
    // reads stay generous because large-workbook extraction is legitimately slow.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT    = Duration.ofSeconds(120);

    @Value("${pe-sub-extraction.base-url}")
    private String extractionBaseUrl;

    @Bean
    public RestClient peSubExtractionClient() {
        String url = Objects.requireNonNull(extractionBaseUrl, "pe-sub-extraction.base-url must be set");
        // Pooled java.net.http.HttpClient: connection reuse and streaming request bodies,
        // unlike SimpleClientHttpRequestFactory (HttpURLConnection) which buffers multipart
        // forwards fully in memory and re-handshakes every call.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
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

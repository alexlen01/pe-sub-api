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

    @Value("${pe-sub-extraction.base-url}")
    private String extractionBaseUrl;

    // Timeouts are configured (see application.yml): without them a hung pe-sub-extraction pins
    // the calling thread forever, and reads must stay generous because large-workbook extraction
    // is legitimately slow.
    @Value("${pe-sub-extraction.connect-timeout}")
    private Duration connectTimeout;

    @Value("${pe-sub-extraction.read-timeout}")
    private Duration readTimeout;

    @Bean
    public RestClient peSubExtractionClient() {
        String url = Objects.requireNonNull(extractionBaseUrl, "pe-sub-extraction.base-url must be set");
        // Pooled java.net.http.HttpClient: connection reuse and streaming request bodies,
        // unlike SimpleClientHttpRequestFactory (HttpURLConnection) which buffers multipart
        // forwards fully in memory and re-handshakes every call.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
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

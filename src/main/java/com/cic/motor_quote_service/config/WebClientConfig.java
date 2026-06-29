package com.cic.motor_quote_service.config;

import com.cic.motor_quote_service.config.properties.DarajaProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Provides the WebClient bean used by DarajaService and any future HTTP integrations.
 *
 * WHY WEBCLIENT AND NOT RESTTEMPLATE?
 *   RestTemplate is in maintenance mode since Spring 5. WebClient is the
 *   supported replacement. It works in both blocking and non-blocking modes —
 *   here we use .block() in DarajaService which is fine for our use case.
 *
 * TIMEOUT RATIONALE (all values in ms unless noted):
 *   connectTimeout 5000ms  — if Safaricom doesn't accept the TCP connection in 5s,
 *                            something is wrong with the network path. Fail fast.
 *   readTimeout    15000ms — STK push responses are usually <1s, but we give 15s
 *                            headroom for Safaricom sandbox slowness.
 *   writeTimeout   5000ms  — writing the request body should never take this long.
 *
 * INTERN: Adjust timeouts downward for production once you've measured real
 * Safaricom latency. 15s read timeout is generous for local dev/sandbox.
 *
 * @EnableConfigurationProperties registers DarajaProperties with the Spring
 * context so @ConfigurationProperties binding works. Without this annotation,
 * the @ConfigurationProperties bean is not auto-detected unless you also
 * add @Component to DarajaProperties — either approach works.
 */
@Configuration
@EnableConfigurationProperties(DarajaProperties.class)
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_S     = 15;
    private static final int WRITE_TIMEOUT_S    = 5;

    /**
     * Shared WebClient with Netty-backed connection pooling and explicit timeouts.
     *
     * A single shared instance is thread-safe and efficient — WebClient reuses
     * Netty's connection pool across all requests. Do not create a new WebClient
     * per request.
     */
    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(Duration.ofSeconds(READ_TIMEOUT_S))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_S, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_S, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
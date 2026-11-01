package com.okexalpha.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.okexalpha.config.OkxProperties;

/** 基于 JDK HttpClient 的实现（OKX 直连可用，无第三方依赖）。 */
@Component
public class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport(OkxProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.readTimeout = Duration.ofMillis(properties.readTimeoutMs());
    }

    private final Duration readTimeout;

    @Override
    public TransportResponse get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(readTimeout);
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new TransportResponse(response.statusCode(), response.body());
    }
}

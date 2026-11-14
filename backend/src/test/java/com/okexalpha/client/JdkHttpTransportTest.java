package com.okexalpha.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.okexalpha.config.OkxProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * 实盘下单链路回归测试：请求必须只带一条 Content-Type。
 * 之前 JdkHttpTransport 在 builder 上无条件 header("Content-Type")，随后又 headers.forEach 追加一次，
 * 导致 OKX 返回 50006 Incorrect Content-Type（2026-11-07 首单探针实测）。
 */
class JdkHttpTransportTest {

    private HttpServer server;
    private volatile List<String> contentTypeValues;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/trade/order", exchange -> {
            contentTypeValues = exchange.getRequestHeaders().get("Content-Type");
            String body = "{\"code\":\"0\",\"msg\":\"\",\"data\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsExactlyOneContentTypeWhenCallerProvidesIt() throws Exception {
        JdkHttpTransport transport = new JdkHttpTransport(properties());
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v5/trade/order");

        transport.post(uri, Map.of("Content-Type", "application/json", "OK-ACCESS-KEY", "k"), "{}");

        assertThat(contentTypeValues).hasSize(1);
        assertThat(contentTypeValues.get(0)).isEqualTo("application/json");
    }

    @Test
    void addsDefaultContentTypeWhenCallerOmitsIt() throws Exception {
        JdkHttpTransport transport = new JdkHttpTransport(properties());
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v5/trade/order");

        transport.post(uri, Map.of("OK-ACCESS-KEY", "k"), "{}");

        assertThat(contentTypeValues).hasSize(1);
        assertThat(contentTypeValues.get(0)).isEqualTo("application/json");
    }

    private OkxProperties properties() {
        OkxProperties properties = mock(OkxProperties.class);
        when(properties.connectTimeoutMs()).thenReturn(3000);
        when(properties.readTimeoutMs()).thenReturn(5000);
        return properties;
    }
}

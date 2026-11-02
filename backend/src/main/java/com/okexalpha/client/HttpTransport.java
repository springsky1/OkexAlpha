package com.okexalpha.client;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** 最小的 HTTP 抽象，便于单测中用假实现替换。 */
public interface HttpTransport {

    TransportResponse get(URI uri, Map<String, String> headers) throws IOException, InterruptedException;

    /** POST（带 JSON body）。默认不支持，便于只实现 GET 的测试替身。 */
    default TransportResponse post(URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("POST is not supported by this transport");
    }

    /** PUT（带 JSON body）。默认不支持。 */
    default TransportResponse put(URI uri, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("PUT is not supported by this transport");
    }
}

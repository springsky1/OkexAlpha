package com.okexalpha.client;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** 最小的 HTTP 抽象，便于单测中用假实现替换。 */
public interface HttpTransport {

    TransportResponse get(URI uri, Map<String, String> headers) throws IOException, InterruptedException;
}

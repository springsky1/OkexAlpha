package com.okexalpha.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 读取 src/test/resources/fixtures 下的 OKX 真实形状样例（纯本地，不联网）。 */
public final class Fixtures {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Fixtures() {
    }

    /** 原始文本。 */
    public static String text(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture: fixtures/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read fixture: fixtures/" + name, ex);
        }
    }

    public static JsonNode json(String name) {
        return MAPPER.readTree(text(name));
    }

    /** 取 OKX 响应体里的 data 数组，转成 List&lt;JsonNode&gt;。 */
    public static List<JsonNode> data(String name) {
        JsonNode root = json(name);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new IllegalStateException("fixture " + name + " has no data array");
        }
        List<JsonNode> list = new ArrayList<>();
        data.forEach(list::add);
        return list;
    }
}

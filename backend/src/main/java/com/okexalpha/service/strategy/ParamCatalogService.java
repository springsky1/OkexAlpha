package com.okexalpha.service.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.okexalpha.error.NotFoundException;
import com.okexalpha.strategy.domain.ParamCatalog;
import com.okexalpha.strategy.domain.ParamSpec;

import static com.okexalpha.strategy.domain.ParamSpec.BODY;
import static com.okexalpha.strategy.domain.ParamSpec.HEADER;
import static com.okexalpha.strategy.domain.ParamSpec.QUERY;

/**
 * 交易参数契约（P0）。所有真正发往 OKX 的私有接口参数都必须先与这里对齐：
 * 参数名写错（例如把 instId 写成 instid）会被 OKX 直接忽略而不是报错，属于最危险的一类 bug，
 * 所以这里把官方文档的字段、必填性、枚举取值集中登记，供
 * <ol>
 *   <li>GET /api/v1/strategy/param-catalog 暴露给前端与人工复核；</li>
 *   <li>LiveOrderService / 影子引擎发送前做白名单校验（未知字段直接拒绝）。</li>
 * </ol>
 *
 * <p>核对来源：docs/OKX-TRADE-PARAMS.md（agent-docs 对照 okx docs-v5 抓取），
 * 核对日期见每个 catalog 的 checkedAt。</p>
 */
@Service
public class ParamCatalogService {

    public static final String ORDER = "/api/v5/trade/order";
    public static final String CLOSE_POSITION = "/api/v5/trade/close-position";
    public static final String SET_LEVERAGE = "/api/v5/account/set-leverage";
    public static final String SET_POSITION_MODE = "/api/v5/account/set-position-mode";
    public static final String POSITIONS = "/api/v5/account/positions";
    public static final String BALANCE = "/api/v5/account/balance";
    public static final String MAX_SIZE = "/api/v5/account/max-size";
    public static final String TICKERS = "/api/v5/market/tickers";
    public static final String MARK_PRICE = "/api/v5/public/mark-price";

    /** 私有接口必须携带的签名头（公有接口不需要）。 */
    private static final List<ParamSpec> PRIVATE_HEADERS = List.of(
            new ParamSpec("OK-ACCESS-KEY", HEADER, true, "string", List.of(), "签名", "API Key"),
            new ParamSpec("OK-ACCESS-SIGN", HEADER, true, "string", List.of(), "签名", "HMAC-SHA256(Base64)，签名串含 timestamp/method/path/body"),
            new ParamSpec("OK-ACCESS-TIMESTAMP", HEADER, true, "string", List.of(), "签名", "ISO 格式毫秒时间戳，与服务端时间偏差需 < 30s"),
            new ParamSpec("OK-ACCESS-PASSPHRASE", HEADER, true, "string", List.of(), "签名", "创建 Key 时设置的口令"),
            new ParamSpec("Content-Type", HEADER, true, "string", List.of("application/json"), "签名", "POST 必填"),
            new ParamSpec("x-simulated-trading", HEADER, false, "enum", List.of("0", "1"), "模拟盘", "1=模拟盘，0/缺省=实盘"));

    private static final List<ParamSpec> PUBLIC_HEADERS = List.of();

    private static final String CHECKED_AT = "2026-11-01";
    private static final String DOC_ORDER = "https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-place-order";
    private static final String DOC_ACCOUNT = "https://www.okx.com/docs-v5/zh/#account";

    private final Map<String, ParamCatalog> catalogs = buildCatalogs();

    public List<ParamCatalog> catalogs() {
        return List.copyOf(catalogs.values());
    }

    public ParamCatalog catalogOf(String endpoint) {
        ParamCatalog catalog = catalogs.get(endpoint);
        if (catalog == null) {
            throw new NotFoundException("no param catalog for endpoint " + endpoint
                    + " (known: " + String.join(",", catalogs.keySet()) + ")");
        }
        return catalog;
    }

    /**
     * 发送前校验：未知字段（最危险）、缺少必填、枚举越界。
     *
     * @param endpoint 接口路径
     * @param fields   实际要发送的字段（名 → 值，值只用于枚举校验，不落日志）
     */
    public Validation validate(String endpoint, Map<String, String> fields) {
        ParamCatalog catalog = catalogOf(endpoint);
        Set<String> known = new LinkedHashSet<>();
        catalog.params().forEach(spec -> known.add(spec.name()));
        List<String> unknown = new ArrayList<>();
        if (fields != null) {
            for (String name : fields.keySet()) {
                if (!known.contains(name)) {
                    unknown.add(name);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (ParamSpec spec : catalog.params()) {
            if (spec.required() && (fields == null || !fields.containsKey(spec.name()))) {
                missing.add(spec.name());
            }
        }
        Map<String, String> invalid = new LinkedHashMap<>();
        if (fields != null) {
            for (ParamSpec spec : catalog.params()) {
                String value = fields.get(spec.name());
                if (value != null && spec.isEnum() && !spec.accepts(value)) {
                    invalid.put(spec.name(), value);
                }
            }
        }
        return new Validation(endpoint, unknown, missing, invalid);
    }

    /**
     * @param unknown 不认识的字段名（应为空；非空说明参数名写错了）
     * @param missing 缺必填
     * @param invalid 枚举越界的字段 → 实际值
     */
    public record Validation(String endpoint, List<String> unknown, List<String> missing,
            Map<String, String> invalid) {

        public boolean ok() {
            return unknown.isEmpty() && missing.isEmpty() && invalid.isEmpty();
        }

        public String describe() {
            List<String> parts = new ArrayList<>();
            if (!unknown.isEmpty()) {
                parts.add("unknown: " + String.join(",", unknown));
            }
            if (!missing.isEmpty()) {
                parts.add("missing: " + String.join(",", missing));
            }
            if (!invalid.isEmpty()) {
                List<String> pairs = new ArrayList<>();
                invalid.forEach((k, v) -> pairs.add(k + "=" + v));
                parts.add("invalid enum: " + String.join(",", pairs));
            }
            return parts.isEmpty() ? "ok" : String.join("; ", parts);
        }
    }

    private Map<String, ParamCatalog> buildCatalogs() {
        Map<String, ParamCatalog> map = new LinkedHashMap<>();

        map.put(ORDER, new ParamCatalog(ORDER, "POST", DOC_ORDER, CHECKED_AT, List.of(
                new ParamSpec("instId", BODY, true, "string", List.of(), "下单", "产品 ID，如 BTC-USDT-SWAP"),
                new ParamSpec("tdMode", BODY, false, "enum", List.of("cash", "cross", "isolated", "spot_isolated"),
                        "下单", "保证金模式：现货 cash / 全仓 cross / 逐仓 isolated（合约必填，现货缺省 cash）"),
                new ParamSpec("side", BODY, true, "enum", List.of("buy", "sell"), "下单", "开平方向由 posSide + reduceOnly 决定"),
                new ParamSpec("ordType", BODY, true, "enum",
                        List.of("market", "limit", "post_only", "fok", "ioc", "optimal_limit_ioc",
                                "mmp", "mmp_and_limit", "mmp_and_post_only"),
                        "下单", "市价单用 market；限价用 limit（市价单在部分品种不受支持）"),
                new ParamSpec("sz", BODY, true, "string", List.of(), "下单", "数量：合约=张数（lotSz 的整数倍且 ≥ minSz），现货=币数；用字符串避免精度丢失"),
                new ParamSpec("px", BODY, false, "string", List.of(), "下单", "委托价：ordType=limit/post_only/fok/ioc 时必填，市价单不填"),
                new ParamSpec("posSide", BODY, false, "enum", List.of("long", "short", "net"), "下单", "单向持仓模式必须传 net（或省略）；双向持仓模式必填 long/short"),
                new ParamSpec("reduceOnly", BODY, false, "boolean", List.of(), "下单", "仅单向持仓（net）模式且合约有效；双向持仓模式不支持"),
                new ParamSpec("tgtCcy", BODY, false, "enum", List.of("base_ccy", "quote_ccy"), "下单", "仅现货市价单：sz 的计价单位"),
                new ParamSpec("banAmend", BODY, false, "boolean", List.of(), "下单", "仅现货市价单且 tgtCcy=quote_ccy：禁止余额自动兑换"),
                new ParamSpec("quickMgnType", BODY, false, "enum", List.of("normal", "auto_borrow", "auto_repay"),
                        "下单", "仅现货杠杆：自动借币/还币"),
                new ParamSpec("clOrdId", BODY, false, "string", List.of(), "下单", "客户自定义 ID，1-32 位字母数字"),
                new ParamSpec("tag", BODY, false, "string", List.of(), "下单", "订单标签"),
                new ParamSpec("attachAlgoOrds", BODY, false, "array", List.of(), "下单", "附带止盈止损/移动止损，优先于已废弃的 tp/sl 顶层字段"),
                new ParamSpec("tpTriggerPx", BODY, false, "string", List.of(), "下单(已废弃)", "改用 attachAlgoOrds"),
                new ParamSpec("tpOrdPx", BODY, false, "string", List.of(), "下单(已废弃)", "改用 attachAlgoOrds"),
                new ParamSpec("slTriggerPx", BODY, false, "string", List.of(), "下单(已废弃)", "改用 attachAlgoOrds"),
                new ParamSpec("slOrdPx", BODY, false, "string", List.of(), "下单(已废弃)", "改用 attachAlgoOrds")),
                PRIVATE_HEADERS, List.of(
                        "sz 与 px 一律用字符串传输，避免 JSON 数字被上游按 double 解析后触发 51121/精度误差",
                        "现货市价买单按 quote_ccy 计（tgtCcy=quote_ccy）更贴近「买 100U」的语义",
                        "合约张数换算见 GET /api/v1/strategy/min-order")));

        map.put(CLOSE_POSITION, new ParamCatalog(CLOSE_POSITION, "POST", DOC_ORDER, CHECKED_AT, List.of(
                new ParamSpec("instId", BODY, true, "string", List.of(), "平仓", "产品 ID"),
                new ParamSpec("mgnMode", BODY, true, "enum", List.of("cross", "isolated"), "平仓", "平仓的保证金模式"),
                new ParamSpec("posSide", BODY, false, "enum", List.of("long", "short"), "平仓", "仅双向持仓模式需要"),
                new ParamSpec("ccy", BODY, false, "string", List.of(), "平仓", "仅全仓模式：指定要平仓的币种"),
                new ParamSpec("autoCxl", BODY, false, "boolean", List.of(), "平仓", "平仓前是否自动撤销该产品的挂单"),
                new ParamSpec("clOrdId", BODY, false, "string", List.of(), "平仓", "客户自定义 ID"),
                new ParamSpec("tag", BODY, false, "string", List.of(), "平仓", "订单标签")),
                PRIVATE_HEADERS, List.of("返回的「平仓成功」只代表请求被受理，成交结果需查 /trade/orders-history")));

        map.put(SET_LEVERAGE, new ParamCatalog(SET_LEVERAGE, "POST", DOC_ACCOUNT, CHECKED_AT, List.of(
                new ParamSpec("instId", BODY, false, "string", List.of(), "设置杠杆", "产品 ID（与 ccy 二选一，合约用 instId）"),
                new ParamSpec("ccy", BODY, false, "string", List.of(), "设置杠杆", "币种（与 instId 二选一，仅杠杆/全仓场景）"),
                new ParamSpec("lever", BODY, true, "string", List.of(), "设置杠杆", "杠杆倍数，如 5"),
                new ParamSpec("mgnMode", BODY, true, "enum", List.of("cross", "isolated"), "设置杠杆", "保证金模式"),
                new ParamSpec("posSide", BODY, false, "enum", List.of("long", "short"),
                        "设置杠杆", "双向持仓模式必填")),
                PRIVATE_HEADERS, List.of("杠杆最大值的权威来源是 /account/instruments 的 lever 字段（本地存量在 okx_instrument.lever）")));

        map.put(SET_POSITION_MODE, new ParamCatalog(SET_POSITION_MODE, "POST", DOC_ACCOUNT, CHECKED_AT, List.of(
                new ParamSpec("posMode", BODY, true, "enum", List.of("long_short_mode", "net_mode"),
                        "持仓模式", "long_short_mode=双向持仓，net_mode=单向持仓")),
                PRIVATE_HEADERS, List.of("有持仓或挂单时无法切换，会返回 51000 类错误")));

        map.put(POSITIONS, new ParamCatalog(POSITIONS, "GET", DOC_ACCOUNT, CHECKED_AT, List.of(
                new ParamSpec("instType", QUERY, false, "enum",
                        List.of("SWAP", "FUTURES", "MARGIN", "OPTION", "ANY"), "持仓", "不传=全部"),
                new ParamSpec("instId", QUERY, false, "string", List.of(), "持仓", "产品 ID，精确查询"),
                new ParamSpec("posId", QUERY, false, "string", List.of(), "持仓", "持仓 ID")),
                PRIVATE_HEADERS, List.of()));

        map.put(BALANCE, new ParamCatalog(BALANCE, "GET", DOC_ACCOUNT, CHECKED_AT, List.of(
                new ParamSpec("ccy", QUERY, false, "string", List.of(), "账户余额", "币种，多个用逗号分隔")),
                PRIVATE_HEADERS, List.of("只返回交易账户，资金账户需 /asset 系列接口")));

        map.put(MAX_SIZE, new ParamCatalog(MAX_SIZE, "GET", DOC_ACCOUNT, CHECKED_AT, List.of(
                new ParamSpec("instId", QUERY, true, "string", List.of(), "最大可开", "产品 ID"),
                new ParamSpec("tdMode", QUERY, true, "enum", List.of("cross", "isolated", "cash"), "最大可开", "保证金模式"),
                new ParamSpec("ccy", QUERY, false, "string", List.of(), "最大可开", "保证金币种"),
                new ParamSpec("px", QUERY, false, "string", List.of(), "最大可开", "限价单委托价"),
                new ParamSpec("leverage", QUERY, false, "string", List.of(), "最大可开", "杠杆倍数"),
                new ParamSpec("unSpotOffset", QUERY, false, "boolean", List.of(), "最大可开", "现货对冲"),
                new ParamSpec("spotHedge", QUERY, false, "boolean", List.of(), "最大可开", "现货对冲")),
                PRIVATE_HEADERS, List.of()));

        map.put(TICKERS, new ParamCatalog(TICKERS, "GET", DOC_ORDER, CHECKED_AT, List.of(
                new ParamSpec("instType", QUERY, true, "enum", List.of("SPOT", "SWAP", "FUTURES", "OPTION"),
                        "行情", "产品类型"),
                new ParamSpec("instFamily", QUERY, false, "string", List.of(), "行情", "交易品种，仅部分 instType 可用"),
                new ParamSpec("uly", QUERY, false, "string", List.of(), "行情", "标的指数")),
                PUBLIC_HEADERS, List.of("返回 last/bidPx/askPx/vol24h/volCcy24h 等，用于最小下单量刷新")));

        map.put(MARK_PRICE, new ParamCatalog(MARK_PRICE, "GET", DOC_ORDER, CHECKED_AT, List.of(
                new ParamSpec("instType", QUERY, true, "enum", List.of("SWAP", "FUTURES", "OPTION", "MARGIN"),
                        "标记价格", "产品类型"),
                new ParamSpec("instId", QUERY, false, "string", List.of(), "标记价格", "产品 ID"),
                new ParamSpec("instFamily", QUERY, false, "string", List.of(), "标记价格", "交易品种")),
                PUBLIC_HEADERS, List.of("强平/浮盈按标记价计算，不要用 last")));

        return map;
    }

    /** 便于拼装请求头的辅助：返回"该接口需要签名"的判断。 */
    public static boolean requiresSignature(String endpoint) {
        return endpoint.startsWith("/api/v5/trade/") || endpoint.startsWith("/api/v5/account/")
                || endpoint.startsWith("/api/v5/asset/");
    }

    public static String normalize(String endpoint) {
        return endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT);
    }
}

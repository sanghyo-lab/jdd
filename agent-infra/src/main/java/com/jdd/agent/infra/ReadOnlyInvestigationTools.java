package com.jdd.agent.infra;

import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationModel.ToolCall;
import com.jdd.agent.domain.InvestigationModel.ToolDefinition;
import com.jdd.agent.domain.InvestigationTools;
import com.jdd.agent.infra.EvidenceToolArguments.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** The model selects a named read, never SQL, commands, arbitrary paths or business writes. */
public final class ReadOnlyInvestigationTools implements InvestigationTools {
    public static final String SCHEMA_VERSION = "commerce-evidence-v2";
    private final CommerceDataTools data;
    private final LogEvidenceTools logs;
    private final SourceEvidenceTools source;
    private final JsonMapper json;
    private final List<ToolDefinition> definitions;

    public ReadOnlyInvestigationTools(CommerceDataTools data, LogEvidenceTools logs, SourceEvidenceTools source, JsonMapper json) {
        this.data = data; this.logs = logs; this.source = source; this.json = json;
        definitions = List.of(
                define("findOrders", "식별자 또는 최대 하루 기간으로 주문 후보를 찾습니다. 조건은 AND이며 원문 행 제한과 잘림을 확인하세요.",
                        "customerId?", "orderId?", "productId?", "requestId?", "checkoutKey?", "from@?", "to@?", "limit#?"),
                define("getOrderContext", "한 주문의 상태·금액·상품·결제·환불·쿠폰 사용·재고 이력을 일관된 DB 스냅샷으로 조회합니다.", "orderId", "limit#?"),
                define("getCouponContext", "고객 또는 발급 쿠폰 ID로 소유·유효기간·설정·사용 이력을 조회합니다.", "customerId?", "customerCouponId?", "limit#?"),
                define("getInventoryContext", "상품 현재 재고, 제한된 원문 이력·주문과 전체 이력·상태별 정확한 수량 합계를 조회합니다.", "productId", "limit#?"),
                define("searchLogs", "JSONL을 식별자 또는 하루 이하 기간으로 AND 검색합니다. 로그의 buildId로 소스를 읽으세요. 동일 eventId는 별도 업무 처리로 세지 마세요.",
                        "buildId?", "requestId?", "orderId?", "productId?", "checkoutKey?", "from@?", "to@?", "limit#?"),
                define("searchCode", "buildId의 해시가 일치하는 실행 소스에서 리터럴을 찾고 주변 줄과 manifest의 policyVersion을 반환합니다. 테스트·시드·재현 제어는 제외합니다.", "buildId", "query", "limit#?"),
                define("readCode", "동일 buildId manifest의 허용 경로를 1부터 시작하는 줄 번호로 최대 300줄 읽습니다.", "buildId", "path", "startLine#", "endLine#"),
                define("readBusinessPolicy", "searchCode/readCode가 반환한 policyVersion과 buildId로 정상 정책을 읽습니다. 새 manifest는 보관 사본·해시를 검증하며 기존 형식은 한계를 명시합니다. section은 실제 확인한 정확한 2단계 제목이며 모르면 null로 전체를 읽으세요. 없는 제목은 정책 근거 없이 제한된 제목 목록을 반환합니다.", "buildId", "version", "section?")
        );
    }
    @Override public List<ToolDefinition> definitions() { return definitions; }
    @Override public List<String> validate(ToolCall call) {
        try { parse(call); return List.of(); }
        catch (RuntimeException invalid) { return List.of("Unknown tool or invalid arguments; follow the bounded schema and provide an identifier or a paired time range"); }
    }
    @Override public Outcome execute(ToolCall call) {
        Object input = parse(call);
        return switch (input) {
            case FindOrders value -> database(data.findOrders(value));
            case OrderContext value -> database(data.orderContext(value));
            case CouponContext value -> database(data.couponContext(value));
            case InventoryContext value -> database(data.inventoryContext(value));
            case SearchLogs value -> logs.search(value);
            case SearchCode value -> source.searchCode(value);
            case ReadCode value -> source.readCode(value);
            case ReadPolicy value -> source.readPolicy(value);
            default -> throw new IllegalArgumentException("Unknown evidence tool");
        };
    }
    private Object parse(ToolCall call) {
        if (call == null || call.name() == null || call.argumentsJson() == null || call.argumentsJson().length() > 8192)
            throw new IllegalArgumentException("Invalid tool call");
        Class<?> type = switch (call.name()) {
            case "findOrders" -> FindOrders.class;
            case "getOrderContext" -> OrderContext.class;
            case "getCouponContext" -> CouponContext.class;
            case "getInventoryContext" -> InventoryContext.class;
            case "searchLogs" -> SearchLogs.class;
            case "searchCode" -> SearchCode.class;
            case "readCode" -> ReadCode.class;
            case "readBusinessPolicy" -> ReadPolicy.class;
            default -> throw new IllegalArgumentException("Unknown evidence tool");
        };
        Object parsed = json.readValue(call.argumentsJson(), type);
        if (parsed == null) throw new IllegalArgumentException("Tool input must be an object");
        return parsed;
    }
    private static Outcome database(List<Observation> observations) {
        return new Outcome(observations, "SELECT 전용 계정의 동일 REPEATABLE READ 스냅샷에서 조회한 " + observations.size()
                + "개 결과를 저장합니다. 원문 행 제한/잘림과 전체 집계를 구분하고 다른 도구 시점과의 차이를 고려하세요.");
    }
    private ToolDefinition define(String name, String description, String... fields) {
        var properties = new LinkedHashMap<String, Object>();
        for (String field : fields) {
            boolean optional = field.endsWith("?");
            String key = field.replace("?", "").replace("#", "").replace("@", "");
            String type = field.contains("#") ? "integer" : "string";
            var schema = new LinkedHashMap<String, Object>();
            schema.put("type", optional ? List.of(type, "null") : type);
            if (type.equals("string")) {
                schema.put("minLength", 1); schema.put("maxLength", key.equals("path") ? 500 : 128);
            } else {
                schema.put("minimum", 1);
                if (key.equals("limit")) schema.put("maximum", name.equals("searchCode") ? 30 : 100);
            }
            if (field.contains("@")) schema.put("format", "date-time");
            properties.put(key, schema);
        }
        return new ToolDefinition(name, description, json.writeValueAsString(Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false)));
    }
}

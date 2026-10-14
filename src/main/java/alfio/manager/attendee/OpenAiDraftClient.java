/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.attendee;

import alfio.model.attendee.Reconciliation.*;
import alfio.util.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

@Component
public class OpenAiDraftClient {
    private final HttpClient client;
    private final AttendeeAssistantSettings settings;
    private final URI endpoint;
    private final Duration timeout;

    @Autowired
    public OpenAiDraftClient(HttpClient client, AttendeeAssistantSettings settings) {
        this(client, settings, settings.endpoint(), settings.timeout());
    }
    OpenAiDraftClient(HttpClient client, AttendeeAssistantSettings settings, URI endpoint, Duration timeout) {
        this.client = client;
        this.settings = settings;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }
    public Draft generate(List<Item> selected) {
        long start = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(payload(selected)), StandardCharsets.UTF_8)).build();
            var response = client.send(request, info -> new LimitedBody());
            if (response.statusCode() == 429) throw error(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMIT");
            if (response.statusCode() != 200) throw error(HttpStatus.BAD_GATEWAY, "AI_FAILED");
            JsonNode root = Json.OBJECT_MAPPER.readTree(response.body());
            if (!"completed".equals(root.path("status").asString())) throw invalid();
            List<String> texts = new ArrayList<>();
            for (JsonNode output : root.path("output")) {
                if (!"message".equals(output.path("type").asString())) continue;
                for (JsonNode content : output.path("content")) {
                    if (!"output_text".equals(content.path("type").asString())) throw invalid();
                    texts.add(content.path("text").asString());
                }
            }
            if (texts.size() != 1) throw invalid();
            var entries = validateAndRender(Json.OBJECT_MAPPER.readTree(texts.getFirst()), selected);
            var usage = root.path("usage");
            int input = usage.path("input_tokens").asInt(-1), output = usage.path("output_tokens").asInt(-1);
            int cached = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
            if (input < 0 || output < 0 || cached < 0 || cached > input) throw invalid();
            return new Draft(entries, settings.model(), input, cached, output,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (HttpTimeoutException ignored) { throw error(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT"); }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.BAD_GATEWAY, "AI_FAILED");
        } catch (ReconciliationException e) { throw e; }
        catch (IOException ignored) { throw error(HttpStatus.BAD_GATEWAY, "AI_FAILED"); }
        catch (RuntimeException ignored) { throw invalid(); }
    }

    Map<String, Object> payload(List<Item> items) {
        // Only opaque references and verified finding types leave the server. No user-authored cells.
        var facts = items.stream().map(i -> Map.of("reference", i.reference(), "findings", i.findings())).toList();
        var entry = objectSchema(Map.of(
            "reference", Map.of("type", "string", "enum", items.stream().map(Item::reference).toList()),
            "findingOrder", Map.of("type", "array", "items", Map.of("type", "string", "enum", Arrays.stream(Finding.values()).map(Enum::name).toList())),
            "style", Map.of("type", "string", "enum", List.of("CONCISE", "POLITE")),
            "detail", Map.of("type", "string", "enum", List.of("BRIEF", "DETAILED"))));
        var schema = objectSchema(Map.of("entries", Map.of("type", "array", "items", entry)));
        return Map.of("model", settings.model(), "store", false, "max_output_tokens", 4096,
            "reasoning", Map.of("effort", "none"),
            "instructions", "You compose Korean event-operation drafts by selecting approved phrase variants. "
                + "Return each input reference exactly once. findingOrder must contain exactly that reference's findings, "
                + "without additions or omissions. Put the most actionable finding first. "
                + "Choose POLITE for attendee-facing requests, CONCISE for internal checks, DETAILED for multiple findings. "
                + "Do not infer cancellation, payment failure, identity or reservation errors. Output only the schema.",
            "input", Json.toJson(Map.of("items", facts)),
            "text", Map.of("format", Map.of("type", "json_schema", "name", "attendee_draft_choices", "strict", true, "schema", schema)));
    }
    private static Map<String, Object> objectSchema(Map<String, Object> fields) {
        return Map.of("type", "object", "properties", fields, "required", fields.keySet().stream().sorted().toList(), "additionalProperties", false);
    }
    private List<DraftEntry> validateAndRender(JsonNode root, List<Item> selected) {
        if (!root.isObject() || root.size() != 1 || !root.path("entries").isArray()
            || root.path("entries").size() != selected.size()) throw invalid();
        var lookup = selected.stream().collect(Collectors.toMap(Item::reference, i -> i));
        var seen = new HashSet<String>();
        List<DraftEntry> result = new ArrayList<>();
        for (JsonNode node : root.path("entries")) {
            String reference = node.path("reference").asString();
            var item = lookup.get(reference);
            if (item == null || !seen.add(reference) || node.size() != 4 || !node.path("findingOrder").isArray()) throw invalid();
            List<Finding> order = new ArrayList<>();
            for (JsonNode finding : node.path("findingOrder")) order.add(Finding.valueOf(finding.asString()));
            if (order.size() != item.findings().size() || new HashSet<>(order).size() != order.size()
                || !new HashSet<>(order).equals(new HashSet<>(item.findings()))) throw invalid();
            String style = node.path("style").asString(), detail = node.path("detail").asString();
            if (!List.of("CONCISE", "POLITE").contains(style) || !List.of("BRIEF", "DETAILED").contains(detail)) throw invalid();
            String explanation = order.stream().map(OpenAiDraftClient::fact).collect(Collectors.joining(" "));
            if (detail.equals("DETAILED")) explanation += " 차이는 비교 시점의 정보이며 예약의 오류나 취소를 뜻하지 않습니다.";
            String nextStep = order.stream().map(OpenAiDraftClient::nextStep).distinct().collect(Collectors.joining(" "));
            String draft = (style.equals("POLITE") ? "안녕하세요. 행사 참석 정보 확인을 부탁드립니다. " : "행사 명단 확인 요청: ")
                + order.stream().map(OpenAiDraftClient::draftClause).collect(Collectors.joining(" "))
                + (style.equals("POLITE") ? " 확인 후 알려주시면 감사하겠습니다." : " 확인 결과를 기록해 주세요.");
            result.add(new DraftEntry(reference, explanation, nextStep, draft));
        }
        return List.copyOf(result);
    }
    private static String fact(Finding finding) {
        return switch (finding) {
            case CSV_ONLY -> "CSV에는 있지만 선택한 행사의 비교 대상 티켓에는 없습니다.";
            case SYSTEM_ONLY -> "시스템의 비교 대상 티켓에는 있지만 CSV에는 없습니다.";
            case DUPLICATE -> "CSV에서 같은 티켓 ID가 여러 번 나타납니다.";
            case EMAIL_MISMATCH -> "CSV와 시스템의 참석자 이메일 값이 다릅니다.";
        };
    }
    private static String nextStep(Finding finding) {
        return switch (finding) {
            case CSV_ONLY -> "선택한 행사, 티켓 ID, CSV 작성 시점과 비교 대상 상태를 확인하세요.";
            case SYSTEM_ONLY -> "CSV 추출 시점과 포함 범위가 현재 비교 대상과 같은지 확인하세요.";
            case DUPLICATE -> "표시된 CSV 레코드를 대조하고 중복 사유를 확인하세요.";
            case EMAIL_MISMATCH -> "양쪽 이메일을 대조하고 어떤 값이 최신 정보인지 확인하세요.";
        };
    }
    private static String draftClause(Finding finding) {
        return switch (finding) {
            case CSV_ONLY -> "운영 명단의 티켓 ID가 선택한 행사의 비교 대상에 없어 행사와 티켓 정보를 확인하려고 합니다.";
            case SYSTEM_ONLY -> "시스템의 티켓이 운영 명단에 없어 명단의 포함 범위를 확인하려고 합니다.";
            case DUPLICATE -> "운영 명단에 같은 티켓 ID가 여러 번 있어 중복 기재 여부를 확인하려고 합니다.";
            case EMAIL_MISMATCH -> "운영 명단과 등록된 참석자 이메일이 달라 현재 사용할 이메일을 확인하려고 합니다.";
        };
    }
    private static ReconciliationException invalid() { return error(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE"); }
    private static ReconciliationException error(HttpStatus status, String code) { return new ReconciliationException(status, code); }

    /** Bound the complete HTTP body, including error responses, without logging or buffering an unbounded body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 64 * 1024) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Response limit exceeded")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

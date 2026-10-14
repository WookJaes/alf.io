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
import alfio.repository.AttendeeReconciliationRepository;
import alfio.repository.AttendeeReconciliationRepository.Stored;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AttendeeReconciliationManager {
    private final AttendeeCsvParser parser;
    private final AttendeeComparator comparator;
    private final AttendeeReconciliationRepository repository;
    private final ReconciliationStore store;
    private final ReconciliationCipher cipher;
    private final AttendeeAssistantSettings settings;
    private final OpenAiDraftClient ai;

    public AttendeeReconciliationManager(AttendeeCsvParser parser, AttendeeComparator comparator,
        AttendeeReconciliationRepository repository, ReconciliationStore store, ReconciliationCipher cipher,
        AttendeeAssistantSettings settings, OpenAiDraftClient ai) {
        this.parser = parser;
        this.comparator = comparator;
        this.repository = repository;
        this.store = store;
        this.cipher = cipher;
        this.settings = settings;
        this.ai = ai;
    }
    public Header compare(int event, String owner, byte[] csv) {
        cipher.requireKey();
        var rows = parser.parse(csv);
        var tickets = repository.tickets(event);
        if (tickets.size() > 20_000) throw error(HttpStatus.CONTENT_TOO_LARGE, "TOO_MANY_TICKETS");
        var result = comparator.compare(rows, tickets.stream().map(t -> new SystemTicket(t.uuid(), t.email(), t.status(), t.reservationId())).toList());
        return header(store.save(event, owner, result), result);
    }
    public List<Header> list(int event, String owner) {
        return store.list(event, owner).stream().map(s -> header(s, alfio.util.Json.fromJson(s.summaryJson(), Summary.class))).toList();
    }
    public Page page(String id, int event, String owner, int page, int pageSize, String filter) {
        if (page < 0 || pageSize < 1 || pageSize > 100) throw error(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        if (!List.of("ALL", "ATTENTION", "MATCHED", "CSV_ONLY", "SYSTEM_ONLY", "DUPLICATE", "EMAIL_MISMATCH").contains(filter)) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_FILTER");
        }
        var stored = store.find(id, event, owner);
        var result = store.decode(stored, event, owner);
        var items = result.items().stream().filter(i -> switch (filter) {
            case "ALL" -> true;
            case "ATTENTION" -> !i.findings().isEmpty();
            case "MATCHED" -> i.findings().isEmpty();
            default -> i.findings().contains(Finding.valueOf(filter));
        }).toList();
        long start = (long) page * pageSize;
        return new Page(header(stored, result), page, pageSize, items.size(),
            start >= items.size() ? List.of() : items.subList((int) start, (int) Math.min(start + pageSize, items.size())));
    }
    public Draft draft(String id, int event, String owner, DraftRequest request) {
        if (!settings.aiAvailable()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED");
        if (request == null || request.references() == null || request.references().isEmpty() || request.references().size() > 20
            || request.references().stream().distinct().count() != request.references().size()) throw error(HttpStatus.BAD_REQUEST, "INVALID_SELECTION");
        var result = store.decode(store.find(id, event, owner), event, owner);
        var selected = result.items().stream().filter(i -> request.references().contains(i.reference()) && !i.findings().isEmpty()).toList();
        if (selected.size() != request.references().size()) throw error(HttpStatus.BAD_REQUEST, "INVALID_SELECTION");
        store.reserveAi(id, event, owner);
        try { return ai.generate(selected); }
        finally { store.releaseAi(id, event, owner); }
    }
    public void delete(String id, int event, String owner) { store.delete(id, event, owner); }
    private Header header(Stored stored, Result result) { return header(stored, result.summary()); }
    private Header header(Stored stored, Summary summary) {
        return new Header(stored.id(), stored.createdAt().toInstant(), stored.expiresAt().toInstant(), summary, settings.aiAvailable());
    }
    private static ReconciliationException error(HttpStatus status, String code) { return new ReconciliationException(status, code); }
}

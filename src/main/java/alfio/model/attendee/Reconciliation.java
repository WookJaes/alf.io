/*
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
package alfio.model.attendee;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Reconciliation {
    private Reconciliation() {}

    public enum Finding { CSV_ONLY, SYSTEM_ONLY, DUPLICATE, EMAIL_MISMATCH }
    public record CsvRow(int rowNumber, String email) {}
    public record SystemTicket(String uuid, String email, String status, String reservationId) {}
    public record Item(String reference, String uuid, List<CsvRow> csvRows, SystemTicket system,
                       List<Finding> findings, boolean emailMissing) {}
    public record Summary(int csvRows, int csvTickets, int systemTickets, int matchedTickets,
                          int attentionTickets, Map<Finding, Long> findings) {}
    public record Result(Summary summary, List<Item> items) {}
    public record Header(String comparisonId, Instant comparedAt, Instant expiresAt, Summary summary,
                         boolean aiAvailable) {}
    public record Page(Header header, int page, int pageSize, int total, List<Item> items) {}
    public record DraftRequest(List<String> references) {}
    public record DraftEntry(String reference, String explanation, String nextStep, String draft) {}
    public record Draft(List<DraftEntry> entries, String model, int inputTokens, int cachedInputTokens,
                        int outputTokens, long durationMillis) {}
    public record Error(String code, Integer rowNumber) {}
}

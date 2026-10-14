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
package alfio.manager.attendee;

import alfio.model.attendee.Reconciliation.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AttendeeComparator {
    public Result compare(List<AttendeeCsvParser.Row> csv, List<SystemTicket> tickets) {
        var rows = csv.stream().collect(Collectors.groupingBy(AttendeeCsvParser.Row::uuid, LinkedHashMap::new, Collectors.toList()));
        var system = tickets.stream().collect(Collectors.toMap(t -> t.uuid().toLowerCase(Locale.ROOT), Function.identity()));
        Set<String> ids = new LinkedHashSet<>(rows.keySet());
        ids.addAll(new TreeSet<>(system.keySet()));
        List<Item> items = new ArrayList<>();
        var counts = new EnumMap<Finding, Long>(Finding.class);
        for (Finding f : Finding.values()) counts.put(f, 0L);
        int matched = 0;
        for (String uuid : ids) {
            var csvRows = rows.getOrDefault(uuid, List.of());
            var ticket = system.get(uuid);
            var findings = new ArrayList<Finding>();
            if (ticket == null) findings.add(Finding.CSV_ONLY);
            if (csvRows.isEmpty()) findings.add(Finding.SYSTEM_ONLY);
            if (csvRows.size() > 1) findings.add(Finding.DUPLICATE);
            String systemEmail = ticket == null || ticket.email() == null ? "" : ticket.email().trim();
            if (ticket != null && csvRows.stream().anyMatch(r -> !r.email().equals(systemEmail))) findings.add(Finding.EMAIL_MISMATCH);
            for (Finding f : findings) counts.merge(f, 1L, Long::sum);
            if (findings.isEmpty()) matched++;
            var missingEmail = ticket != null && systemEmail.isEmpty() || csvRows.stream().anyMatch(r -> r.email().isEmpty());
            items.add(new Item("F-" + (items.size() + 1), uuid,
                csvRows.stream().map(r -> new CsvRow(r.rowNumber(), r.email())).toList(), ticket, List.copyOf(findings), missingEmail));
        }
        return new Result(new Summary(csv.size(), rows.size(), system.size(), matched, items.size() - matched,
            Collections.unmodifiableMap(counts)), List.copyOf(items));
    }
}

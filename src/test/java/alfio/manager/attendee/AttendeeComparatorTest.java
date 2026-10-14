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
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttendeeComparatorTest {
    private final AttendeeComparator comparator = new AttendeeComparator();
    @Test void distinguishesAllFindingsAndKeepsBothRowsWhenDuplicateAlsoMismatches() {
        var result = comparator.compare(List.of(
            new AttendeeCsvParser.Row(2,"a","a@example.invalid"), new AttendeeCsvParser.Row(3,"b","b@example.invalid"),
            new AttendeeCsvParser.Row(4,"b","different@example.invalid"), new AttendeeCsvParser.Row(5,"c","c@example.invalid")),
            List.of(new SystemTicket("a","a@example.invalid","ACQUIRED","shared"),
                new SystemTicket("b","b@example.invalid","CHECKED_IN","shared"), new SystemTicket("d","d@example.invalid","TO_BE_PAID","other")));
        assertEquals(1, result.summary().matchedTickets()); assertEquals(3, result.summary().attentionTickets());
        for (Finding f : Finding.values()) assertEquals(1L, result.summary().findings().get(f));
        var duplicate = result.items().get(1);
        assertEquals(List.of(Finding.DUPLICATE,Finding.EMAIL_MISMATCH), duplicate.findings());
        assertEquals(List.of(3,4), duplicate.csvRows().stream().map(CsvRow::rowNumber).toList());
        assertEquals("shared", result.items().getFirst().system().reservationId());
        assertEquals("shared", duplicate.system().reservationId());
    }
    @Test void neverJoinsByEmailAndDoesNotCollapseTicketsFromOneReservation() {
        var result = comparator.compare(List.of(new AttendeeCsvParser.Row(2,"unknown","same@example.invalid")),
            List.of(new SystemTicket("one","same@example.invalid","ACQUIRED","r"), new SystemTicket("two","same@example.invalid","ACQUIRED","r")));
        assertEquals(3, result.items().size()); assertEquals(2L, result.summary().findings().get(Finding.SYSTEM_ONLY));
        assertEquals(1L, result.summary().findings().get(Finding.CSV_ONLY));
    }
    @Test void trimsSystemEmailButPreservesCaseDifferenceAndFlagsMissingEmail() {
        var result = comparator.compare(List.of(new AttendeeCsvParser.Row(2,"a","a@example.invalid"),new AttendeeCsvParser.Row(3,"b","")),
            List.of(new SystemTicket("a"," A@example.invalid ","ACQUIRED","r"),new SystemTicket("b",null,"ACQUIRED","r")));
        assertEquals(List.of(Finding.EMAIL_MISMATCH),result.items().getFirst().findings());
        assertTrue(result.items().getLast().emailMissing()); assertTrue(result.items().getLast().findings().isEmpty());
    }
}

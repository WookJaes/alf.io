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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AttendeeCsvParserTest {
    private final AttendeeCsvParser parser = new AttendeeCsvParser();
    private static final String ID = "10000000-0000-0000-0000-000000000001";
    private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void acceptsExportBomReorderedColumnsQuotedFieldsAndBlankEmail() {
        var rows = parser.parse(bytes("\uFEFFE-Mail, Other ,ID\r\n\" a@example.invalid \",\"a,b\"," + ID.toUpperCase() + "\r\n,\"two\nlines\"," + ID));
        assertEquals(2, rows.size()); assertEquals(2, rows.getFirst().rowNumber());
        assertEquals(3, rows.getLast().rowNumber()); assertEquals(ID, rows.getFirst().uuid());
        assertEquals("a@example.invalid", rows.getFirst().email()); assertEquals("", rows.getLast().email());
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "ID,E-Mail", "\uFEFFID,E-Mail\n"})
    void rejectsEmptyInput(String input) { assertEquals("EMPTY_CSV", assertThrows(ReconciliationException.class, () -> parser.parse(bytes(input))).getMessage()); }
    static java.util.stream.Stream<Arguments> invalidInputs() {
        return java.util.stream.Stream.of(
            Arguments.of("ID,Name\nfoo,bar", "MISSING_COLUMNS"),
            Arguments.of("ID,ID,E-Mail\nfoo,foo,bar", "INVALID_HEADERS"),
            Arguments.of("ID,,E-Mail\nfoo,foo,bar", "INVALID_HEADERS"),
            Arguments.of("ID,E-Mail\nfoo,bar", "INVALID_ID"),
            Arguments.of("ID,E-Mail\n1-1-1-1-1,bar", "INVALID_ID"),
            Arguments.of("ID,E-Mail\n" + ID + ",a,b", "COLUMN_COUNT"),
            Arguments.of("ID,E-Mail\n" + ID, "COLUMN_COUNT"));
    }
    @ParameterizedTest @MethodSource("invalidInputs")
    void givesSafeActionableErrors(String input, String code) {
        var error = assertThrows(ReconciliationException.class, () -> parser.parse(bytes(input)));
        assertEquals(code, error.getMessage()); assertNotNull(error.rowNumber()); assertNull(error.getCause());
    }
    @Test void rejectsMalformedQuotingAndUtf8WithoutOriginalValues() {
        var error = assertThrows(ReconciliationException.class, () -> parser.parse(bytes("ID,E-Mail\n" + ID + ",\"private-unclosed")));
        assertEquals("INVALID_CSV", error.getMessage());
        assertEquals("INVALID_CSV", assertThrows(ReconciliationException.class, () -> parser.parse(new byte[] {(byte) 0xff})).getMessage());
    }
    @Test void enforcesByteRowAndFieldLimits() {
        assertEquals("FILE_TOO_LARGE", assertThrows(ReconciliationException.class, () -> parser.parse(new byte[AttendeeCsvParser.MAX_BYTES + 1])).getMessage());
        assertEquals("TOO_MANY_ROWS", assertThrows(ReconciliationException.class, () -> parser.parse(bytes("ID,E-Mail\n" + (ID + ",\n").repeat(20_001)))).getMessage());
        assertEquals("INVALID_FIELD", assertThrows(ReconciliationException.class, () -> parser.parse(bytes("ID,E-Mail\n" + ID + "," + "x".repeat(4097)))).getMessage());
    }
}

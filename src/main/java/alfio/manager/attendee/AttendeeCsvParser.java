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

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvReadFeature;
import tools.jackson.dataformat.csv.CsvSchema;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class AttendeeCsvParser {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;
    public record Row(int rowNumber, String uuid, String email) {}

    public List<Row> parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw error("FILE_TOO_LARGE", null);
        int rowNumber = 1;
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), decoder);
             tools.jackson.databind.MappingIterator<List<String>> iterator = new CsvMapper().readerForListOf(String.class)
                 .with(CsvSchema.emptySchema().withoutHeader()).with(CsvReadFeature.WRAP_AS_ARRAY).readValues(reader)) {
            if (!iterator.hasNextValue()) throw error("EMPTY_CSV", null);
            List<String> headers = iterator.nextValue();
            headers = new ArrayList<>(headers);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replaceFirst("^\uFEFF", ""));
            headers.replaceAll(String::trim);
            if (headers.size() > 100 || headers.stream().anyMatch(String::isEmpty)
                || new HashSet<>(headers).size() != headers.size()) throw error("INVALID_HEADERS", 1);
            int id = headers.indexOf("ID");
            int email = headers.indexOf("E-Mail");
            if (id < 0 || email < 0) throw error("MISSING_COLUMNS", 1);
            List<Row> rows = new ArrayList<>();
            while (iterator.hasNextValue()) {
                rowNumber++;
                if (rows.size() >= MAX_ROWS) throw error("TOO_MANY_ROWS", rowNumber);
                List<String> values = iterator.nextValue();
                if (values.size() != headers.size()) throw error("COLUMN_COUNT", rowNumber);
                if (values.stream().anyMatch(v -> v.length() > 4096 || v.indexOf('\0') >= 0)) throw error("INVALID_FIELD", rowNumber);
                String uuid = values.get(id).trim();
                if (!uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw error("INVALID_ID", rowNumber);
                rows.add(new Row(rowNumber, UUID.fromString(uuid).toString(), values.get(email).trim()));
            }
            if (rows.isEmpty()) throw error("EMPTY_CSV", null);
            return List.copyOf(rows);
        } catch (ReconciliationException e) {
            throw e;
        } catch (Exception ignored) {
            throw error("INVALID_CSV", rowNumber);
        }
    }

    private static ReconciliationException error(String code, Integer row) {
        return new ReconciliationException(HttpStatus.BAD_REQUEST, code, row);
    }
}

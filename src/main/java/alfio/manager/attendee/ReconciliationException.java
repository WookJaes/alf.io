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

import org.springframework.http.HttpStatus;

/** Contains only safe error codes, never CSV values or upstream error bodies. */
public class ReconciliationException extends RuntimeException {
    private final HttpStatus status;
    private final Integer rowNumber;

    public ReconciliationException(HttpStatus status, String code) { this(status, code, null); }
    public ReconciliationException(HttpStatus status, String code, Integer rowNumber) {
        super(code);
        this.status = status;
        this.rowNumber = rowNumber;
    }
    public HttpStatus status() { return status; }
    public Integer rowNumber() { return rowNumber; }
}

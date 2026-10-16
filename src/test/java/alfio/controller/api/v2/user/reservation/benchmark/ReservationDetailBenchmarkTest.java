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
package alfio.controller.api.v2.user.reservation.benchmark;

import alfio.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReservationDetailBenchmarkTest {
    private byte[] canonical(String json) {
        return Json.OBJECT_MAPPER.writeValueAsBytes(ReservationDetailBenchmark.canonicalValue(Json.OBJECT_MAPPER.readTree(json)));
    }

    @Test
    void ignoresObjectKeyOrderAtEveryDepth() {
        assertArrayEquals(canonical("{\"b\":[{\"x\":true,\"y\":null}],\"a\":1}"),
            canonical("{\"a\":1,\"b\":[{\"y\":null,\"x\":true}]}"));
    }

    @Test
    void preservesArrayOrderAndScalarValues() {
        assertFalse(java.util.Arrays.equals(canonical("[1,2]"), canonical("[2,1]")));
        assertFalse(java.util.Arrays.equals(canonical("{\"a\":true}"), canonical("{\"a\":false}")));
        assertFalse(java.util.Arrays.equals(canonical("{\"a\":null}"), canonical("{\"a\":0}")));
    }
}

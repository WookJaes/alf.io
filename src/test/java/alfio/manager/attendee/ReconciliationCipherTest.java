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
import org.springframework.mock.env.MockEnvironment;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationCipherTest {
    private ReconciliationCipher cipher(String key) {
        return new ReconciliationCipher(new AttendeeAssistantSettings(new MockEnvironment().withProperty("alfio.attendee-assistant.encryption-key",key)));
    }
    @Test void encryptsWithRandomNonceAndBindsResultToEventUserAndId() {
        var cipher = cipher(Base64.getEncoder().encodeToString(new byte[32]));
        String plaintext = "attendee@example.invalid";
        String first = cipher.encrypt(plaintext,"id:event:user"), second = cipher.encrypt(plaintext,"id:event:user");
        assertNotEquals(first,second); assertFalse(first.contains(plaintext)); assertEquals(plaintext,cipher.decrypt(first,"id:event:user"));
        assertThrows(ReconciliationException.class, () -> cipher.decrypt(first,"id:other:user"));
        var tampered = Base64.getDecoder().decode(first); tampered[tampered.length - 1] ^= 1;
        assertThrows(ReconciliationException.class, () -> cipher.decrypt(Base64.getEncoder().encodeToString(tampered),"id:event:user"));
    }
    @Test void failsClosedForMissingOrIncorrectKeys() {
        assertEquals("STORAGE_NOT_CONFIGURED",assertThrows(ReconciliationException.class, () -> cipher("").requireKey()).getMessage());
        assertThrows(ReconciliationException.class, () -> cipher("not-base64").requireKey());
        assertThrows(ReconciliationException.class, () -> cipher(Base64.getEncoder().encodeToString(new byte[16])).requireKey());
    }
}

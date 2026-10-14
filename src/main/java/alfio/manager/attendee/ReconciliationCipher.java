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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class ReconciliationCipher {
    private final AttendeeAssistantSettings settings;
    private final SecureRandom random = new SecureRandom();
    public ReconciliationCipher(AttendeeAssistantSettings settings) { this.settings = settings; }

    public void requireKey() { key(); }
    private SecretKeySpec key() {
        try {
            byte[] key = Base64.getDecoder().decode(settings.encryptionKey());
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException ignored) {
            throw new ReconciliationException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_CONFIGURED");
        }
    }

    public String encrypt(String value, String context) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (ReconciliationException e) { throw e; }
        catch (Exception ignored) { throw unavailable(); }
    }

    public String decrypt(String value, String context) {
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            if (combined.length < 28) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(combined, 12)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (ReconciliationException e) { throw e; }
        catch (Exception ignored) { throw unavailable(); }
    }
    private static ReconciliationException unavailable() {
        return new ReconciliationException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
    }
}

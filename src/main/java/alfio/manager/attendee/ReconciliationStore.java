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
import alfio.repository.AttendeeReconciliationRepository;
import alfio.repository.AttendeeReconciliationRepository.Stored;
import alfio.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class ReconciliationStore {
    private final AttendeeReconciliationRepository repository;
    private final ReconciliationCipher cipher;
    public ReconciliationStore(AttendeeReconciliationRepository repository, ReconciliationCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }
    private String context(String id, int event, String owner) { return id + ":" + event + ":" + owner; }

    @Transactional
    public Stored save(int event, String owner, Result result) {
        String id = UUID.randomUUID().toString();
        String json = Json.toJson(result);
        if (json.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024) throw error(HttpStatus.CONTENT_TOO_LARGE, "RESULT_TOO_LARGE");
        String encrypted = cipher.encrypt(json, context(id, event, owner));
        repository.lockCreation();
        repository.deleteExpired();
        if (repository.countForOwner(event, owner) >= 10 || repository.totalBytes() + encrypted.length() > 100 * 1024 * 1024) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "STORAGE_LIMIT");
        }
        repository.insert(id, event, owner, encrypted, Json.toJson(result.summary()));
        return find(id, event, owner);
    }
    public Stored find(String id, int event, String owner) {
        return repository.find(id, event, owner).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "COMPARISON_NOT_FOUND"));
    }
    public Result decode(Stored stored, int event, String owner) {
        try { return Json.fromJson(cipher.decrypt(stored.encryptedResult(), context(stored.id(), event, owner)), Result.class); }
        catch (ReconciliationException e) { throw e; }
        catch (RuntimeException ignored) { throw error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE"); }
    }
    public List<Stored> list(int event, String owner) { return repository.list(event, owner); }
    public void delete(String id, int event, String owner) { repository.delete(id, event, owner); }

    @Transactional
    public void reserveAi(String id, int event, String owner) {
        if (repository.acquireAi(id, event, owner) != 1) throw error(HttpStatus.CONFLICT, "AI_BUSY");
        // Fixed lock order: global counter first, then user counter; rollback both on rejection.
        if (repository.reserveQuota("global", 100) != 1 || repository.reserveQuota("user:" + digest(owner), 10) != 1) {
            throw error(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMIT");
        }
    }
    public void releaseAi(String id, int event, String owner) { repository.releaseAi(id, event, owner); }
    private static String digest(String owner) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ignored) { throw new IllegalStateException("Digest unavailable"); }
    }
    private static ReconciliationException error(HttpStatus status, String code) { return new ReconciliationException(status, code); }
}

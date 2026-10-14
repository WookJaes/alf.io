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
package alfio.controller.api.admin;

import alfio.manager.AccessService;
import alfio.manager.attendee.AttendeeCsvParser;
import alfio.manager.attendee.AttendeeReconciliationManager;
import alfio.manager.attendee.ReconciliationException;
import alfio.model.attendee.Reconciliation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/admin/api/events/{eventName}/attendee-reconciliations")
public class AttendeeReconciliationApiController {
    private final AccessService access;
    private final AttendeeReconciliationManager manager;
    public AttendeeReconciliationApiController(AccessService access, AttendeeReconciliationManager manager) {
        this.access = access;
        this.manager = manager;
    }
    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
    @PostMapping(consumes = "text/csv")
    public Header compare(@PathVariable String eventName, HttpServletRequest request, Principal principal) {
        var event = access.checkEventOwnership(principal, eventName);
        if (request.getContentLengthLong() > AttendeeCsvParser.MAX_BYTES) throw tooLarge();
        try {
            var bytes = request.getInputStream().readNBytes(AttendeeCsvParser.MAX_BYTES + 1);
            if (bytes.length > AttendeeCsvParser.MAX_BYTES) throw tooLarge();
            return manager.compare(event.getId(), principal.getName(), bytes);
        } catch (IOException ignored) {
            throw new ReconciliationException(HttpStatus.BAD_REQUEST, "INVALID_CSV");
        }
    }
    @GetMapping
    public List<Header> list(@PathVariable String eventName, Principal principal) {
        return manager.list(access.checkEventOwnership(principal, eventName).getId(), principal.getName());
    }
    @GetMapping("/{id}")
    public Page page(@PathVariable String eventName, @PathVariable String id, Principal principal,
                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize,
                     @RequestParam(defaultValue = "ATTENTION") String filter) {
        return manager.page(id, access.checkEventOwnership(principal, eventName).getId(), principal.getName(), page, pageSize, filter);
    }
    @PostMapping("/{id}/drafts")
    public Draft draft(@PathVariable String eventName, @PathVariable String id, Principal principal, HttpServletRequest request) {
        var event = access.checkEventOwnership(principal, eventName);
        DraftRequest selection;
        try {
            byte[] body = request.getInputStream().readNBytes(8193);
            if (body.length > 8192) throw new IllegalArgumentException();
            selection = alfio.util.Json.fromJson(new String(body, java.nio.charset.StandardCharsets.UTF_8), DraftRequest.class);
        } catch (Exception ignored) {
            throw new ReconciliationException(HttpStatus.BAD_REQUEST, "INVALID_SELECTION");
        }
        return manager.draft(id, event.getId(), principal.getName(), selection);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String eventName, @PathVariable String id, Principal principal) {
        manager.delete(id, access.checkEventOwnership(principal, eventName).getId(), principal.getName());
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(ReconciliationException.class)
    public ResponseEntity<alfio.model.attendee.Reconciliation.Error> failure(ReconciliationException error) {
        return ResponseEntity.status(error.status()).body(new alfio.model.attendee.Reconciliation.Error(error.getMessage(), error.rowNumber()));
    }
    private static ReconciliationException tooLarge() { return new ReconciliationException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE"); }
}

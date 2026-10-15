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
package alfio.e2e;

import alfio.manager.support.response.ValidatedResponse;
import alfio.model.result.ValidationResult;
import alfio.util.Json;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static alfio.e2e.ReservationLoadValidationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Response classification checks; no running app, browser or database is needed. */
class ReservationLoadMetricsTest {
    private static final String SOLD_OUT = "error.STEP_1_NOT_ENOUGH_TICKETS";

    private static java.util.List<Attempt> measuredAttempts(int failures, String outcome) {
        var attempts = new java.util.ArrayList<Attempt>();
        for (int i = 0; i < 1000; i++) {
            attempts.add(new Attempt(i < failures ? outcome : i < failures + 100 ? "complete" : "inventory_rejection",
                1, "", "", ""));
        }
        return attempts;
    }

    @Test
    void oneUnexpectedOutcomeCannotPassEvenWithAllTicketsComplete() {
        for (var outcome : Set.of("server_error", "unexpected_response", "client_error", "timeout")) {
            assertThrows(AssertionError.class, () -> requireSuccessfulRun(measuredAttempts(1, outcome),
                new DbSnapshot(100, 100, 0, 0, 0)), outcome);
        }
    }

    @Test
    void moreThanFivePercentErrorsCannotReturnSuccess() {
        assertThrows(AssertionError.class, () -> requireSuccessfulRun(measuredAttempts(51, "server_error"),
            new DbSnapshot(100, 100, 0, 0, 0)));
    }

    @Test
    void allRequestsFailingWithUntouchedInventoryCannotPass() {
        assertThrows(AssertionError.class, () -> requireSuccessfulRun(measuredAttempts(1000, "server_error"),
            new DbSnapshot(0, 0, 100, 0, 0)));
    }

    @Test
    void expectedSuccessAndSoldOutCountsPassOnlyWithFullyAllocatedInventory() {
        var attempts = measuredAttempts(0, "server_error");
        assertDoesNotThrow(() -> requireSuccessfulRun(attempts, new DbSnapshot(100, 100, 0, 0, 0)));
        assertThrows(AssertionError.class, () -> requireSuccessfulRun(attempts, new DbSnapshot(99, 99, 1, 0, 0)));
    }

    private static Response validationFailure(int status, String... codes) {
        var descriptors = java.util.Arrays.stream(codes)
            .map(code -> new ValidationResult.ErrorDescriptor("reservation", "", code))
            .toArray(ValidationResult.ErrorDescriptor[]::new);
        return new Response(status, Json.OBJECT_MAPPER.valueToTree(
            new ValidatedResponse<String>(ValidationResult.failed(descriptors), null)));
    }

    private static Response response(int status, String json) {
        return new Response(status, Json.OBJECT_MAPPER.readTree(json));
    }

    @Test
    void expectedSoldOutUsesThePublicApiValidationEnvelope() {
        var response = validationFailure(422, SOLD_OUT);
        assertEquals(Set.of(SOLD_OUT), errors(response));
        assertTrue(inventoryRejection(response));
        assertThrows(UnexpectedResponse.class, () -> requireOk(response),
            "Expected sold-out rejections must remain separate from completed reservations");
    }

    @Test
    void unavailableCategoryIsAnExpectedCandidateOnlyForThePrevalidatedLoadFixture() {
        // The load test has already validated the category, sale window and one-ticket request.
        // Its final database assertion must additionally prove that all inventory was consumed.
        var response = validationFailure(422, "error.STEP_1_TICKET_CATEGORY_MUST_BE_SALEABLE");
        assertTrue(inventoryRejection(response));
        assertThrows(UnexpectedResponse.class, () -> requireOk(response));
    }

    @Test
    void unrelatedClientFailuresAreNotSoldOutEvenIfTheirBodyMentionsInventory() {
        for (int status : new int[] {400, 401, 403, 404, 409, 429}) {
            var response = validationFailure(status, SOLD_OUT);
            assertFalse(inventoryRejection(response), "HTTP " + status + " must count as an unexpected response");
            assertThrows(UnexpectedResponse.class, () -> requireOk(response));
        }
    }

    @Test
    void serverFailuresCannotBeHiddenByAnInventoryCode() {
        for (int status : new int[] {500, 502, 503, 504}) {
            var response = validationFailure(status, SOLD_OUT);
            assertFalse(inventoryRejection(response), "HTTP " + status + " must count as a server failure");
            assertThrows(UnexpectedResponse.class, () -> requireOk(response));
        }
    }

    @Test
    void invalidTicketSelectionIsNotExpectedInventoryRejection() {
        var response = validationFailure(422, "error.STEP_1_SELECT_AT_LEAST_ONE");
        assertFalse(inventoryRejection(response));
        assertThrows(UnexpectedResponse.class, () -> requireOk(response));
    }

    @Test
    void perReservationQuantityLimitIsNotInventoryExhaustion() {
        var response = new Response(422, Json.OBJECT_MAPPER.valueToTree(
            new ValidatedResponse<String>(ValidationResult.failed(
                new ValidationResult.ErrorDescriptor("reservation", "", "error.STEP_1_OVER_MAXIMUM", new Object[] {5})), null)));
        assertEquals(5, response.body().path("validationErrors").get(0).path("arguments").path("0").asInt());
        assertFalse(inventoryRejection(response), "A configured purchase limit does not prove that inventory is exhausted");
        assertThrows(UnexpectedResponse.class, () -> requireOk(response));
    }

    @Test
    void anUnknownValidationFailureIsNotExpectedInventoryRejection() {
        var response = validationFailure(422, "error.unrecognized");
        assertFalse(inventoryRejection(response));
        assertThrows(UnexpectedResponse.class, () -> requireOk(response));
    }

    @Test
    void mixedInventoryAndInvalidInputDoesNotBecomeExpectedRejection() {
        var response = validationFailure(422, SOLD_OUT, "error.STEP_1_SELECT_AT_LEAST_ONE");
        assertEquals(Set.of(SOLD_OUT, "error.STEP_1_SELECT_AT_LEAST_ONE"), errors(response));
        assertFalse(inventoryRejection(response), "All validation errors must be understood before treating a refusal as expected");
    }

    @Test
    void emptyErrorListDoesNotExplainARefusal() {
        var response = response(422, "{\"success\":false,\"errorCount\":1,\"validationErrors\":[]}");
        assertTrue(errors(response).isEmpty());
        assertFalse(inventoryRejection(response));
    }

    @Test
    void missingErrorListDoesNotExplainARefusal() {
        assertFalse(inventoryRejection(response(422, "{\"success\":false,\"errorCount\":1}")));
    }

    @Test
    void blankOrMissingErrorCodesDoNotExplainARefusal() {
        for (String descriptor : new String[] {"{}", "{\"code\":\"\"}", "{\"code\":null}"}) {
            assertFalse(inventoryRejection(response(422,
                "{\"success\":false,\"errorCount\":1,\"validationErrors\":[" + descriptor + "]}")), descriptor);
        }
    }

    @Test
    void incompleteErrorDescriptorCannotHideBesideSoldOut() {
        var response = response(422,
            "{\"success\":false,\"errorCount\":2,\"validationErrors\":[{\"code\":\"" + SOLD_OUT + "\"},{}]}");
        assertFalse(inventoryRejection(response));
    }

    @Test
    void anHttpSuccessWithValidationFailureIsNotSuccessfulReservation() {
        var response = validationFailure(200, "error.STEP_1_SELECT_AT_LEAST_ONE");
        assertFalse(inventoryRejection(response));
        assertThrows(UnexpectedResponse.class, () -> requireOk(response));
    }

    @Test
    void contradictorySuccessAndErrorEnvelopeIsNotSuccessfulReservation() {
        var response = response(200,
            "{\"success\":true,\"errorCount\":1,\"validationErrors\":[{\"code\":\"error.STEP_1_SELECT_AT_LEAST_ONE\"}]}");
        assertThrows(UnexpectedResponse.class, () -> requireOk(response));
    }

    @Test
    void aContradictorySuccessfulEnvelopeIsNotExpectedSoldOut() {
        var response = response(422,
            "{\"success\":true,\"errorCount\":1,\"validationErrors\":[{\"code\":\"" + SOLD_OUT + "\"}]}");
        assertFalse(inventoryRejection(response));
    }

    @Test
    void missingSuccessFlagDoesNotConfirmAnExpectedValidationRefusal() {
        assertFalse(inventoryRejection(response(422,
            "{\"errorCount\":1,\"validationErrors\":[{\"code\":\"" + SOLD_OUT + "\"}]}")));
    }

    @Test
    void zeroErrorCountDoesNotConfirmAnExpectedValidationRefusal() {
        assertFalse(inventoryRejection(response(422,
            "{\"success\":false,\"errorCount\":0,\"validationErrors\":[{\"code\":\"" + SOLD_OUT + "\"}]}")));
    }

    @Test
    void successfulApiEnvelopeAndPlainLookupPayloadPassHttpValidation() {
        var reservation = new Response(200, Json.OBJECT_MAPPER.valueToTree(
            new ValidatedResponse<>(ValidationResult.success(), "synthetic-reservation")));
        assertDoesNotThrow(() -> requireOk(reservation));
        assertFalse(inventoryRejection(reservation));
        // Some lookup endpoints return their payload directly rather than ValidatedResponse.
        assertDoesNotThrow(() -> requireOk(response(200, "{\"ticketsByCategory\":[]}")));
    }
}

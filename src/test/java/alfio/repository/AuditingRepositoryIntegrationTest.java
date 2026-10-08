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
package alfio.repository;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.manager.EventManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.user.UserManager;
import alfio.model.Audit;
import alfio.model.TicketCategory;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static alfio.test.util.IntegrationTestUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class AuditingRepositoryIntegrationTest {
    @Autowired private AuditingRepository auditingRepository;
    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserManager userManager;
    @Autowired private EventManager eventManager;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketCategoryRepository ticketCategoryRepository;
    @Autowired private TicketReservationManager reservationManager;

    @ParameterizedTest
    @CsvSource({
        "UTC, 2026-10-08", "Asia/Seoul, 2026-10-08",
        "Pacific/Honolulu, 2026-10-08", "Europe/Zurich, 2026-10-08",
        "Europe/Zurich, 2026-03-29", "Europe/Zurich, 2026-10-25"
    })
    void countScansWithinEventDayIncludingMidnightAndDst(String zone, LocalDate day) {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        var now = ZonedDateTime.now(ClockProvider.clock());
        var categoryModification = new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT,
            AVAILABLE_SEATS, DateTimeModification.fromZonedDateTime(now.minusDays(1)),
            DateTimeModification.fromZonedDateTime(now.plusDays(1)), DESCRIPTION, BigDecimal.TEN, false,
            "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty());
        var eventAndUser = initEvent(List.of(categoryModification), organizationRepository, userManager, eventManager, eventRepository);
        var event = eventAndUser.getLeft();
        var category = ticketCategoryRepository.findAllTicketCategories(event.getId()).getFirst();
        var modification = new TicketReservationModification();
        modification.setTicketCategoryId(category.getId());
        modification.setQuantity(1);
        var reservationId = reservationManager.createTicketReservation(event,
            List.of(new TicketReservationWithOptionalCodeModification(modification, Optional.empty())), List.of(),
            Date.from(now.plusDays(1).toInstant()), Optional.empty(), Locale.ENGLISH, false, null);

        var start = day.atStartOfDay(ZoneId.of(zone));
        var end = day.plusDays(1).atStartOfDay(ZoneId.of(zone));
        for (var instant : List.of(start.toInstant().minusMillis(1), start.toInstant(),
                                  end.toInstant().minusMillis(1), end.toInstant())) {
            auditingRepository.insert(reservationId, null, event.getId(), Audit.EventType.BADGE_SCAN,
                Date.from(instant), Audit.EntityType.TICKET, "1");
        }
        // An unrelated audit entry must not affect duplicate-scan detection.
        auditingRepository.insert(reservationId, null, event.getId(), Audit.EventType.REVERT_CHECK_IN,
            Date.from(start.plusHours(1).toInstant()), Audit.EntityType.TICKET, "1");
        var scanTypes = Set.of(Audit.EventType.BADGE_SCAN.name());
        assertEquals(2, auditingRepository.countAuditsOfTypesInTheSameDay(reservationId, scanTypes, start.plusHours(12)));
        assertEquals(1, auditingRepository.countAuditsOfTypesInTheSameDay(reservationId, scanTypes, end.plusHours(12)));
    }
}

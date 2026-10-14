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
import static org.junit.jupiter.api.Assertions.*;

class AttendeeAssistantSettingsTest {
    @Test void defaultsToDisabledAndOfficialEndpoint() {
        var settings=new AttendeeAssistantSettings(new MockEnvironment());
        assertFalse(settings.aiAvailable());assertEquals("https://api.openai.com/v1/responses",settings.endpoint().toString());
        assertEquals("gpt-5.4-mini-2026-03-17",settings.model());assertEquals(30,settings.timeout().toSeconds());
    }
    @Test void restrictsTestOverridesToDevAndLoopback() {
        var environment=new MockEnvironment().withProperty("alfio.attendee-assistant.test-endpoint","http://127.0.0.1:1234/responses");
        var settings=new AttendeeAssistantSettings(environment);
        assertThrows(IllegalArgumentException.class,settings::endpoint);environment.setActiveProfiles("dev");
        assertEquals("127.0.0.1",settings.endpoint().getHost());
        for(String address:java.util.List.of("http://example.invalid/responses","http://127.0.0.1:1234@other.invalid/responses","file:///tmp/key")) {
            environment.setProperty("alfio.attendee-assistant.test-endpoint",address);assertThrows(IllegalArgumentException.class,settings::endpoint);
        }
        environment.setProperty("alfio.attendee-assistant.timeout-seconds","31");assertThrows(IllegalArgumentException.class,settings::timeout);
    }
}

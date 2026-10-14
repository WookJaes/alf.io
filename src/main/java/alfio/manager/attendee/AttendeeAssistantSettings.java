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

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AttendeeAssistantSettings {
    private final Environment environment;
    public AttendeeAssistantSettings(Environment environment) { this.environment = environment; }
    public String encryptionKey() { return environment.getProperty("alfio.attendee-assistant.encryption-key", ""); }
    public String apiKey() { return environment.getProperty("alfio.attendee-assistant.openai-api-key", ""); }
    public String model() { return environment.getProperty("alfio.attendee-assistant.model", "gpt-5.4-mini-2026-03-17"); }
    public java.net.URI endpoint() {
        String configured = environment.getProperty("alfio.attendee-assistant.test-endpoint", "");
        if (configured.isBlank()) return java.net.URI.create("https://api.openai.com/v1/responses");
        var uri = java.net.URI.create(configured);
        if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))
            || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
            || uri.getPort() <= 0 || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Test endpoint requires the dev profile and an IPv4 loopback URL");
        }
        return uri;
    }
    public java.time.Duration timeout() {
        int seconds = environment.getProperty("alfio.attendee-assistant.timeout-seconds", Integer.class, 30);
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("AI timeout must be between 1 and 30 seconds");
        return java.time.Duration.ofSeconds(seconds);
    }
    public boolean aiAvailable() {
        return environment.getProperty("alfio.attendee-assistant.ai-enabled", Boolean.class, false) && !apiKey().isBlank();
    }
}

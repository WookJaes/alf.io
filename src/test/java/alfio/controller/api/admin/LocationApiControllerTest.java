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
package alfio.controller.api.admin;

import alfio.config.MvcConfiguration;
import alfio.manager.system.ConfigurationManager;
import alfio.model.system.ConfigurationKeyValuePathLevel;
import alfio.model.system.ConfigurationKeys;
import alfio.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public class LocationApiControllerTest {
    private ConfigurationManager configuration;
    private MockMvc mvc;
    private static final String PATH = "/admin/api/location/static-map-image";

    public static MockMvc mvc(ConfigurationManager configuration) {
        var converters = new ArrayList<HttpMessageConverter<?>>();
        new MvcConfiguration(new MockEnvironment(), "validation", Json.OBJECT_MAPPER).configureMessageConverters(converters);
        return MockMvcBuilders.standaloneSetup(new LocationApiController(configuration))
            .setMessageConverters(converters.toArray(HttpMessageConverter<?>[]::new)).build();
    }

    public static Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> settings(String provider) {
        return Map.of(MAPS_PROVIDER, value(MAPS_PROVIDER, provider),
            MAPS_CLIENT_API_KEY, value(MAPS_CLIENT_API_KEY, "synthetic-map-key"),
            MAPS_HERE_API_KEY, value(MAPS_HERE_API_KEY, "synthetic-map-key"));
    }

    private static ConfigurationManager.MaybeConfiguration value(ConfigurationKeys key, String value) {
        return new ConfigurationManager.MaybeConfiguration(key, new ConfigurationKeyValuePathLevel(key.name(), value, null));
    }

    @BeforeEach
    void setup() {
        configuration = mock(ConfigurationManager.class);
        when(configuration.getFor(anySet(), any())).thenReturn(settings("GOOGLE"));
        mvc = mvc(configuration);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/json", "application/json, text/plain, */*", "*/*"})
    void validCoordinatesKeepStringContract(String accept) throws Exception {
        var response = mvc.perform(get(PATH).param("lat", "37.5").param("lng", "127.0").header("Accept", accept)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertFalse(response.getContentType().contains("html"));
        String url = response.getContentType().startsWith("application/json")
            ? Json.fromJson(response.getContentAsString(), String.class) : response.getContentAsString();
        assertTrue(url.startsWith("https://maps.googleapis.com/maps/api/staticmap?center=37.5,127.0&key="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0005", "-0.0009", "-0.0005", "1e-7", "-1e-7", "0.001", "5e-324", "-5e-324", "1e-999999999", "-1e-999999999"})
    void smallCoordinatesUseBoundedPlainDecimalUrls(String coordinate) throws Exception {
        for (var provider : new String[]{"GOOGLE", "HERE"}) {
            when(configuration.getFor(anySet(), any())).thenReturn(settings(provider));
            for (var accept : new String[]{"text/plain", "application/json"}) {
                var response = mvc.perform(get(PATH).param("lat", coordinate).param("lng", coordinate).accept(accept))
                    .andReturn().getResponse();
                assertEquals(200, response.getStatus());
                String url = response.getContentType().startsWith("application/json")
                    ? Json.fromJson(response.getContentAsString(), String.class) : response.getContentAsString();
                assertTrue(url.length() < 2000, "URL size stays bounded for extreme negative exponents");
                String firstQuery = URI.create(url).getRawQuery().split("&")[0];
                assertTrue(firstQuery.startsWith(provider.equals("GOOGLE") ? "center=" : "c="));
                String coordinates = firstQuery.substring(firstQuery.indexOf('=') + 1);
                String[] pair = coordinates.split(",");
                assertEquals(2, pair.length);
                for (var value : pair) {
                    assertTrue(value.matches("-?[0-9]+[.][0-9]+"), "Expected plain decimal: " + value);
                    assertTrue(value.length() <= 330, "Validated double output must stay bounded");
                    assertEquals(0, new BigDecimal(value).compareTo(BigDecimal.valueOf(Double.parseDouble(coordinate))));
                }
                assertTrue(provider.equals("GOOGLE") ? url.endsWith(coordinates) : url.contains("&poi=" + coordinates + "&"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-90", "90", "0", "+45.25", "1e1", ".5", "-0.0"})
    void latitudeBoundariesAndDecimalForms(String latitude) throws Exception {
        assertEquals(200, mvc.perform(get(PATH).param("lat", latitude).param("lng", "180").accept("text/plain")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get(PATH).param("lat", latitude).param("lng", "-180").accept("text/plain")).andReturn().getResponse().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "north", "NaN", "Infinity", "-Infinity", "1e999", "90.0001", "-90.0001", "90.00000000000000001", "-90.00000000000000001", "0x1p0", "1d", "1&zoom=1", "${key}", "<script>document.title='XSS_LOCAL_MARKER'</script>"})
    void invalidLatitudesAreControlledBadRequests(String latitude) throws Exception {
        for (var accept : new String[]{"text/plain", "application/json", "application/json, text/plain, */*"}) {
            var response = mvc.perform(get(PATH).param("lat", latitude).param("lng", "1").header("Accept", accept)).andReturn().getResponse();
            assertEquals(400, response.getStatus());
            assertTrue(response.getContentType().startsWith("text/plain"));
            assertEquals("Invalid map coordinates", response.getContentAsString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"180.0001", "-180.0001", "180.00000000000000001", "-180.00000000000000001", "NaN", "Infinity", "1e999", "1&key=x", "<img src=x onerror=document.title=1>"})
    void invalidLongitudesAreBadRequests(String longitude) throws Exception {
        assertEquals(400, mvc.perform(get(PATH).param("lat", "1").param("lng", longitude).accept("text/plain")).andReturn().getResponse().getStatus());
    }

    @Test
    void missingCoordinatesAreBadRequests() throws Exception {
        assertEquals(400, mvc.perform(get(PATH).accept("application/json")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get(PATH).param("lat", "1").accept("application/json")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get(PATH).param("lng", "1").accept("application/json")).andReturn().getResponse().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xhtml+xml", "image/png"})
    void unsupportedFormatsAreNotAcceptable(String accept) throws Exception {
        for (var lat : new String[]{"37.5", "<script>document.title='XSS_LOCAL_MARKER'</script>"}) {
            var response = mvc.perform(get(PATH).param("lat", lat).param("lng", "1").header("Accept", accept)).andReturn().getResponse();
            assertEquals(406, response.getStatus());
            assertTrue(response.getContentType().startsWith("text/plain"));
            assertEquals("", response.getContentAsString());
        }
    }

    @Test
    void browserNavigationDoesNotReceiveHtml() throws Exception {
        var response = mvc.perform(get(PATH).param("lat", "37.5").param("lng", "1")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertFalse(response.getContentType().contains("html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOGLE", "HERE", "NONE"})
    void providerConfigurationRemainsEffective(String provider) throws Exception {
        when(configuration.getFor(anySet(), any())).thenReturn(settings(provider));
        var response = mvc.perform(get(PATH).param("lat", "1").param("lng", "2").accept("text/plain")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        if (provider.equals("NONE")) {
            assertEquals("", response.getContentAsString());
        } else {
            assertTrue(response.getContentAsString().contains(provider.equals("GOOGLE") ? "center=1.0,2.0" : "c=1.0,2.0"));
        }
    }

    @Test
    void wildcardRequestsRespectExplicitFormatExclusions() throws Exception {
        var plain = mvc.perform(get(PATH).param("lat", "1").param("lng", "2")
            .header("Accept", "application/json;q=0, */*;q=1")).andReturn().getResponse();
        assertEquals(200, plain.getStatus());
        assertTrue(plain.getContentType().startsWith("text/plain"));
        var denied = mvc.perform(get(PATH).param("lat", "1").param("lng", "2")
            .header("Accept", "application/json;q=0, text/plain;q=0, */*;q=1")).andReturn().getResponse();
        assertEquals(406, denied.getStatus());
        var noHeader = mvc.perform(get(PATH).param("lat", "1").param("lng", "2")).andReturn().getResponse();
        assertEquals(200, noHeader.getStatus());
        assertTrue(noHeader.getContentType().startsWith("application/json"));
    }

    @Test
    void internalFailureDoesNotReflectExceptionMessage() throws Exception {
        when(configuration.getFor(anySet(), any())).thenThrow(new IllegalStateException("<script>internal-synthetic-marker</script>"));
        var response = mvc.perform(get(PATH).param("lat", "1").param("lng", "2").accept("application/json")).andReturn().getResponse();
        assertEquals(500, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/plain"));
        assertEquals("Unable to generate map URL", response.getContentAsString());
    }
}

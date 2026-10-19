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

import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.model.modification.support.LocationDescriptor;
import alfio.model.system.ConfigurationKeys;
import com.moodysalem.TimezoneMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

import static alfio.model.system.ConfigurationKeys.*;

@RestController
@RequestMapping("/admin/api")
public class LocationApiController {

    private static final Logger log = LoggerFactory.getLogger(LocationApiController.class);
    private static final Pattern COORDINATE = Pattern.compile("[+-]?(?:[0-9]+(?:[.][0-9]*)?|[.][0-9]+)(?:[eE][+-]?[0-9]+)?");
    private final ConfigurationManager configurationManager;

    @Autowired
    public LocationApiController(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unhandledException(Exception e) {
       log.error("Exception in location api", e);
        return e.getMessage();
    }

    @GetMapping("/location/timezones")
    public List<String> getTimezones() {
        List<String> s = new ArrayList<>(ZoneId.getAvailableZoneIds());
        s.sort(String::compareTo);
        return s;
    }

    @GetMapping("/location/timezone")
    public String getTimezone(@RequestParam("lat") double lat, @RequestParam("lng") double lng) {
        String tzId = TimezoneMapper.tzNameAt(lat, lng);
        return getTimezones().contains(tzId) ? tzId : null;
    }



    @GetMapping("/location/static-map-image")
    public ResponseEntity<String> getMapImage(
        @RequestParam(name = "lat", required = false) String lat,
        @RequestParam(name = "lng", required = false) String lng,
        HttpServletRequest request) {
        String accept = String.join(",", Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
        MediaType responseType = mapResponseType(accept.isBlank() ? MediaType.ALL_VALUE : accept);
        if (responseType == null) {
            // Do not use sendError: the full app renders it as an HTML error page.
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).contentType(MediaType.TEXT_PLAIN).build();
        }
        String latitude = normalizeCoordinate(lat, 90);
        String longitude = normalizeCoordinate(lng, 180);
        if (latitude == null || longitude == null) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Invalid map coordinates");
        }
        try {
            return ResponseEntity.ok().contentType(responseType).body(LocationDescriptor.getMapUrl(latitude, longitude, getGeoConf()));
        } catch (RuntimeException e) {
            // Do not let the common handler reflect configuration or provider errors into this response.
            log.error("Unable to generate static map URL", e);
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN).body("Unable to generate map URL");
        }
    }

    private static MediaType mapResponseType(String accept) {
        try {
            var requested = MediaType.parseMediaTypes(accept);
            MediaType selected = null;
            double quality = 0;
            for (var supported : List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)) {
                MediaType match = null;
                for (var range : requested) {
                    if (range.isCompatibleWith(supported) && (match == null || range.removeQualityValue().isMoreSpecific(match.removeQualityValue()))) {
                        match = range;
                    }
                }
                // A specific q=0 excludes that format even if a wildcard would otherwise allow it.
                if (match != null && match.getQualityValue() > quality) {
                    selected = supported;
                    quality = match.getQualityValue();
                }
            }
            return selected;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeCoordinate(String coordinate, int limit) {
        if (coordinate == null || coordinate.length() > 64 || !COORDINATE.matcher(coordinate).matches()) {
            return null;
        }
        try {
            double value = Double.parseDouble(coordinate);
            // Decimal comparison also rejects out-of-range values that round to a valid double boundary.
            if (!Double.isFinite(value) || new BigDecimal(coordinate).abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
                return null;
            }
            // Convert only the validated double: plain decimals without unbounded input exponent expansion.
            return BigDecimal.valueOf(value).toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<ConfigurationKeys, ConfigurationManager.MaybeConfiguration> getGeoConf() {
        var keys = Set.of(MAPS_PROVIDER, MAPS_CLIENT_API_KEY, MAPS_HERE_API_KEY);
        return configurationManager.getFor(keys, ConfigurationLevel.system());
    }

    @GetMapping("/location/map-provider-client-api-key")
    public ProviderAndKeys getGeoInfoProviderAndKeys() {
        var geoInfoConfiguration = getGeoConf();
        ConfigurationKeys.GeoInfoProvider provider = LocationDescriptor.getProvider(geoInfoConfiguration);
        Map<ConfigurationKeys, String> apiKeys = new EnumMap<>(ConfigurationKeys.class);
        geoInfoConfiguration.forEach((k,v) -> v.getValue().ifPresent(value -> apiKeys.put(k, value)));
        return new ProviderAndKeys(provider, apiKeys);
    }

    @AllArgsConstructor
    @Getter
    public static class ProviderAndKeys {
        private final ConfigurationKeys.GeoInfoProvider provider;
        private Map<ConfigurationKeys, String> keys;
    }
}

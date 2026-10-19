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
package alfio.manager;

import alfio.model.modification.UploadBase64FileModification;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

public class FileDownloadManager {

    private static final Pattern NUMERIC_HOST = Pattern.compile("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*");
    private static final Pattern IPV4_HOST = Pattern.compile("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
    private static final Logger log = LoggerFactory.getLogger(FileDownloadManager.class);
    private final HostResolver dnsResolver;

    public FileDownloadManager() {
        this(InetAddress::getAllByName);
    }

    // Package-private seam for deterministic DNS tests; production always uses the system resolver.
    FileDownloadManager(HostResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    public DownloadedFile downloadFile(String url) {
        URI toFetch = URI.create(requireNonNull(StringUtils.trimToNull(url)));
        String scheme = toFetch.getScheme();
        Assert.isTrue(scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")),
            "scheme must be http or https");
        String host = toFetch.getHost();
        if (host == null || toFetch.getRawUserInfo() != null || host.contains("%")
            || (NUMERIC_HOST.matcher(host).matches() && !IPV4_HOST.matcher(host).matches())) {
            return null;
        }
        String dnsHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] addresses = dnsResolver.resolve(dnsHost);
            if (addresses == null || addresses.length == 0) {
                return null;
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    return null;
                }
            }
            // Keep the original host for Host/SNI/certificate verification, but connect only
            // to this validated snapshot. No second system DNS lookup, proxy or redirect.
            InetAddress[] pinned = addresses.clone();
            for (int i = 0; i < pinned.length; i++) {
                // Preserve the URL hostname in the resolved socket address for TLS peer identity.
                // getByAddress(host, bytes) never performs a DNS lookup.
                pinned[i] = InetAddress.getByAddress(dnsHost, pinned[i].getAddress());
            }
            SocketAddressResolver pinnedResolver = (requestedHost, port, context, promise) -> {
                if (!dnsHost.equalsIgnoreCase(requestedHost)) {
                    promise.failed(new UnknownHostException("Unexpected image host"));
                } else {
                    promise.succeeded(Arrays.stream(pinned).map(address -> new InetSocketAddress(address, port)).toList());
                }
            };
            try (var client = imageClient(pinnedResolver)) {
                client.start();
                var listener = new InputStreamResponseListener();
                client.newRequest(toFetch).timeout(30, TimeUnit.SECONDS).send(listener);
                var response = listener.get(30, TimeUnit.SECONDS);
                try (var body = listener.getInputStream()) {
                    if (response.getStatus() < 200 || response.getStatus() >= 300) {
                        return null;
                    }
                    String[] parts = Pattern.compile("/").split(url);
                    var contentType = response.getHeaders().get(HttpHeader.CONTENT_TYPE);
                    return new DownloadedFile(body.readAllBytes(), parts[parts.length - 1],
                        contentType == null ? "application/octet-stream" : contentType);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            // Do not log user URLs, DNS answers or server response data.
            log.warn("Unable to download image");
            return null;
        }
    }

    HttpClient imageClient(SocketAddressResolver pinnedResolver) {
        var client = new HttpClient();
        client.setSocketAddressResolver(pinnedResolver);
        client.setFollowRedirects(false);
        client.setConnectTimeout(10_000);
        client.setIdleTimeout(30_000);
        return client;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0 && first != 10 && first != 127 && first < 224
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2))
                    || (second == 88 && third == 99)))
                && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                && !(first == 203 && second == 0 && third == 113);
        }
        // Only global unicast IPv6; exclude special-purpose, documentation and transition ranges.
        return bytes.length == 16 && (first & 0xe0) == 0x20
            && !(first == 0x20 && second == 0x01 && (Byte.toUnsignedInt(bytes[2]) < 2
                || (bytes[2] == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)))
            && !(first == 0x20 && second == 0x02)
            && !(first == 0x3f && second == 0xff && (Byte.toUnsignedInt(bytes[2]) & 0xf0) == 0);
    }

    @Getter
    @AllArgsConstructor
    public static class DownloadedFile {
        private byte[] file;
        private String name;
        private String type;

        public UploadBase64FileModification toUploadBase64FileModification() {
            UploadBase64FileModification uf = new UploadBase64FileModification();
            uf.setFile(file);
            uf.setName(name);
            uf.setType(type);
            return uf;
        }
    }

}

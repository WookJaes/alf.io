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

import alfio.controller.api.v1.admin.EventApiV1Controller;
import alfio.controller.api.v1.admin.SubscriptionApiV1Controller;
import alfio.model.modification.UploadBase64FileModification;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileDownloadManagerTest {
    @Test
    void internalImageMustNotReachServerThroughDownloaderOrControllers() throws Exception {
        var requests = new AtomicInteger();
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal.png", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.size());
            exchange.getResponseBody().write(png.toByteArray());
            exchange.close();
        });
        server.start();
        try {
            var downloader = new FileDownloadManager();
            var uploads = mock(FileUploadManager.class);
            when(uploads.ensureIsAnImage(any())).thenCallRealMethod();
            when(uploads.insertFile(any(UploadBase64FileModification.class))).thenReturn("synthetic-image");
            var event = new EventApiV1Controller(null, null, uploads, downloader, null, null, null, null, null, null, null, null, null, null);
            var subscription = new SubscriptionApiV1Controller(null, uploads, downloader, null, null, null);
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/internal.png";
            // Run all three real download paths before asserting, including on the vulnerable code.
            var direct = downloader.downloadFile(url);
            Object eventImage = ReflectionTestUtils.invokeMethod(event, "fetchImage", url);
            Object subscriptionImage = ReflectionTestUtils.invokeMethod(subscription, "fetchImage", url);
            assertAll(
                () -> assertNull(direct),
                () -> assertNull(eventImage),
                () -> assertNull(subscriptionImage),
                () -> assertEquals(0, requests.get(), "requests received by loopback server"),
                () -> verify(uploads, never()).insertFile(any())
            );
        } finally {
            server.stop(0);
        }
    }
    @Test
    void ipv6LoopbackServerReceivesNoRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("::1", 0), 0);
        server.createContext("/image.png", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            assertNull(new FileDownloadManager().downloadFile("http://[::1]:" + server.getAddress().getPort() + "/image.png"));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInternalAndSpecialAddressRanges() throws Exception {
        var blocked = List.of("0.0.0.0", "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.254",
            "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.254", "192.168.1.1",
            "192.0.0.8", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.254",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd00::1", "fe80::1", "fec0::1", "ff02::1", "::127.0.0.1",
            "::ffff:127.0.0.1", "::ffff:192.168.1.1", "64:ff9b::7f00:1", "64:ff9b:1::1",
            "2001::1", "2001:2::1", "2001:db8::1", "2002:7f00:1::", "3fff::1");
        for (var ip : blocked) {
            assertFalse(FileDownloadManager.isPublicAddress(InetAddress.getByName(ip)), ip);
        }
    }

    @Test
    void allowsPublicAddressesAtRangeBoundaries() throws Exception {
        for (var ip : List.of("8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.0", "172.15.255.255",
            "172.32.0.0", "198.17.255.255", "198.20.0.0", "2001:4860:4860::8888", "2606:4700:4700::1111")) {
            assertTrue(FileDownloadManager.isPublicAddress(InetAddress.getByName(ip)), ip);
        }
    }

    @Test
    void loopbackRepresentationsNeverReachRealServer() throws Exception {
        try (var fixture = new ImageServer()) {
            var downloader = new FileDownloadManager() {
                @Override
                HttpClient imageClient(SocketAddressResolver resolver) {
                    fail("internal or ambiguous literal must be rejected before transport creation");
                    return null;
                }
            };
            for (var host : List.of("127.0.0.1", "127.1", "2130706433", "0177.0.0.1", "0x7f000001", "0x7f.0.0.1", "127.000.000.001",
                "localhost", "[::1]", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]", "[::127.0.0.1]")) {
                assertNull(downloader.downloadFile(fixture.url(host, "/image.png")), host);
            }
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void rejectsMixedDnsAnswersInEitherOrderBeforeCreatingTransport() throws Exception {
        var publicAddress = InetAddress.getByName("8.8.8.8");
        var privateAddress = InetAddress.getByName("127.0.0.1");
        for (var answers : List.of(new InetAddress[] {publicAddress, privateAddress},
            new InetAddress[] {privateAddress, publicAddress}, new InetAddress[] {InetAddress.getByName("::1")})) {
            var downloader = new FileDownloadManager(host -> answers) {
                @Override
                HttpClient imageClient(
                        SocketAddressResolver resolver) {
                    fail("blocked DNS answers must not create a transport");
                    return null;
                }
            };
            assertNull(downloader.downloadFile("http://image.example/image.png"));
        }
    }

    @Test
    void dnsFailuresAndEmptyAnswersFailClosed() {
        for (FileDownloadManager.HostResolver resolver : List.<FileDownloadManager.HostResolver>of(
            host -> { throw new UnknownHostException("synthetic DNS failure"); },
            host -> new InetAddress[0], host -> null)) {
            assertNull(new FileDownloadManager(resolver).downloadFile("http://image.example/image.png"));
        }
    }

    @Test
    void rejectsAmbiguousAuthorityAndScopedIpv6WithoutDns() {
        var downloader = new FileDownloadManager(host -> { fail("must reject before DNS"); return null; });
        for (var url : List.of("http://user@image.example/image.png", "http://[fe80::1%25test]/image.png",
            "http://127.0.0.1%2e/image.png", "http:///image.png")) {
            assertNull(downloader.downloadFile(url));
        }
        assertThrows(IllegalArgumentException.class, () -> downloader.downloadFile("file:///image.png"));
    }

    @Test
    void pinsDnsSnapshotAndPreservesNormalImagePaths() throws Exception {
        try (var fixture = new ImageServer()) {
            var lookups = new AtomicInteger();
            var connections = new AtomicInteger();
            var downloader = ImageDownloadTestSupport.routedDownloader(host -> {
                // A second lookup for the same download would rebind to loopback.
                int count = lookups.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName(count % 2 == 1 ? "8.8.8.8" : "127.0.0.1")};
            }, fixture.server.getAddress().getPort(), endpoint -> {
                assertEquals("8.8.8.8", endpoint.getAddress().getHostAddress());
                connections.incrementAndGet();
            });
            String url = fixture.url("image.example", "/image.png");
            var file = downloader.downloadFile(url);
            assertNotNull(file);
            assertArrayEquals(fixture.png, file.getFile());
            assertEquals("image.png", file.getName());
            assertEquals("image/png", file.getType());
            assertEquals(1, lookups.get());
            assertEquals(1, connections.get());
            assertEquals(1, fixture.requests.get());
            assertEquals("image.example:" + fixture.server.getAddress().getPort(), fixture.hostHeader);
            // Next independent download must resolve again and reject the changed DNS answer.
            assertNull(downloader.downloadFile(url));
            assertEquals(2, lookups.get());
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void publicIpv6DnsAnswerUsesPinnedConnection() throws Exception {
        try (var fixture = new ImageServer()) {
            var publicAddress = InetAddress.getByName("2606:4700:4700::1111");
            var downloader = ImageDownloadTestSupport.routedDownloader(
                host -> new InetAddress[] {publicAddress}, fixture.server.getAddress().getPort(),
                endpoint -> assertEquals(publicAddress, endpoint.getAddress()));
            assertNotNull(downloader.downloadFile(fixture.url("image.example", "/image.png")));
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void normalImagesStillPassEventAndSubscriptionImageValidation() throws Exception {
        try (var fixture = new ImageServer()) {
            var downloader = ImageDownloadTestSupport.routedDownloader(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")}, fixture.server.getAddress().getPort(),
                endpoint -> assertEquals("8.8.8.8", endpoint.getAddress().getHostAddress()));
            var uploads = mock(FileUploadManager.class);
            when(uploads.ensureIsAnImage(any())).thenCallRealMethod();
            when(uploads.insertFile(any())).thenReturn("synthetic-image");
            var event = new EventApiV1Controller(null, null, uploads, downloader, null, null, null, null, null, null, null, null, null, null);
            var subscription = new SubscriptionApiV1Controller(null, uploads, downloader, null, null, null);
            var url = fixture.url("image.example", "/image.png");
            assertEquals("synthetic-image", ReflectionTestUtils.invokeMethod(event, "fetchImage", url));
            assertEquals("synthetic-image", ReflectionTestUtils.invokeMethod(subscription, "fetchImage", url));
            assertEquals(2, fixture.requests.get());
            verify(uploads, times(2)).insertFile(any());
        }
    }

    @Test
    void httpsPreservesCertificateHostnameAndSni() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var name = new X500Name("CN=image.example");
        var now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
            Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)), name, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(new GeneralName(
                GeneralName.dNSName, "image.example")));
        var certificate = new JcaX509CertificateConverter().getCertificate(builder.build(
            new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        char[] syntheticPassword = "synthetic-test-only".toCharArray();
        store.setKeyEntry("synthetic", pair.getPrivate(), syntheticPassword, new Certificate[] {certificate});
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, syntheticPassword);
        var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("synthetic", certificate);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        var requests = new AtomicInteger();
        var sni = new AtomicReference<String>();
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        server.createContext("/image.png", exchange -> {
            requests.incrementAndGet();
            var session = (ExtendedSSLSession) ((HttpsExchange) exchange).getSSLSession();
            sni.set(((SNIHostName) session.getRequestedServerNames().getFirst()).getAsciiName());
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, output.size());
            exchange.getResponseBody().write(output.toByteArray());
            exchange.close();
        });
        server.start();
        try {
            var downloader = ImageDownloadTestSupport.routedDownloader(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")}, server.getAddress().getPort(),
                endpoint -> assertEquals("8.8.8.8", endpoint.getAddress().getHostAddress()), client -> {
                    var tls = new SslContextFactory.Client();
                    tls.setTrustStore(trustStore);
                    assertEquals("HTTPS", tls.getEndpointIdentificationAlgorithm());
                    client.setSslContextFactory(tls);
                });
            var file = downloader.downloadFile("https://image.example:" + server.getAddress().getPort() + "/image.png");
            assertNotNull(file);
            assertArrayEquals(output.toByteArray(), file.getFile());
            assertEquals("image.example", sni.get());
            assertNull(downloader.downloadFile("https://wrong.example:" + server.getAddress().getPort() + "/image.png"));
            assertEquals(1, requests.get(), "hostname mismatch must fail before HTTP");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectsAreNotFollowedEvenToLoopback() throws Exception {
        try (var fixture = new ImageServer()) {
            var downloader = ImageDownloadTestSupport.routedDownloader(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")}, fixture.server.getAddress().getPort(),
                endpoint -> assertEquals("8.8.8.8", endpoint.getAddress().getHostAddress()));
            for (int status : List.of(301, 302, 303, 307, 308)) {
                var path = "/redirect/" + status;
                fixture.server.createContext(path, exchange -> {
                    fixture.redirects.incrementAndGet();
                    exchange.getResponseHeaders().add("Location", fixture.url("127.0.0.1", "/image.png"));
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
                assertNull(downloader.downloadFile(fixture.url("image.example", path)));
            }
            assertEquals(5, fixture.redirects.get());
            assertEquals(0, fixture.requests.get());
        }
    }

    private static final class ImageServer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger redirects = new AtomicInteger();
        final byte[] png;
        volatile String hostHeader;

        ImageServer() throws Exception {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
            png = output.toByteArray();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/image.png", exchange -> {
                requests.incrementAndGet();
                hostHeader = exchange.getRequestHeaders().getFirst("Host");
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
                exchange.close();
            });
            server.start();
        }

        String url(String host, String path) {
            return "http://" + host + ":" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

}

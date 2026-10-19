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

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.SocketAddressResolver;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ImageDownloadTestSupport {
    private ImageDownloadTestSupport() {
    }

    // Real HTTP to synthetic loopback data; ONLY the selected socket addresses are remapped.
    // Assert the production resolver's public snapshot before routing to the local server.
    static FileDownloadManager routedDownloader(FileDownloadManager.HostResolver resolver, int localPort,
                                                Consumer<InetSocketAddress> selectedAddress) {
        return routedDownloader(resolver, localPort, selectedAddress, client -> {});
    }

    static FileDownloadManager routedDownloader(FileDownloadManager.HostResolver resolver, int localPort,
                                                Consumer<InetSocketAddress> selectedAddress, Consumer<HttpClient> configure) {
        return new FileDownloadManager(resolver) {
            @Override
            HttpClient imageClient(SocketAddressResolver pinnedResolver) {
                var client = super.imageClient((host, port, context, promise) -> pinnedResolver.resolve(host, port, context,
                    new Promise<>() {
                        @Override
                        public void succeeded(List<InetSocketAddress> addresses) {
                            addresses.forEach(address -> {
                                assertEquals(host, HostPort.normalizeHost(address.getHostString()), "preserve TLS peer hostname in pinned address");
                                selectedAddress.accept(address);
                            });
                            try {
                                promise.succeeded(List.of(new InetSocketAddress(
                                    InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}), localPort)));
                            } catch (UnknownHostException exception) {
                                promise.failed(exception);
                            }
                        }

                        @Override
                        public void failed(Throwable failure) {
                            promise.failed(failure);
                        }
                    }));
                configure.accept(client);
                return client;
            }
        };
    }
}

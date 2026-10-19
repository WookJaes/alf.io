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

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@WireMockTest
class FileDownloadManagerIntegrationTest {

    private FileDownloadManager downloader(WireMockRuntimeInfo info) {
        return ImageDownloadTestSupport.routedDownloader(host -> new InetAddress[] {
            InetAddress.getByAddress(new byte[] {8, 8, 8, 8})
        }, info.getHttpPort(), endpoint -> assertEquals("8.8.8.8", endpoint.getAddress().getHostAddress()));
    }

    @Test
    void testFileDownloadSuccess(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(get("/test.txt")
            .willReturn(ok("Hello World!")
                .withHeader("Content-Type", "text/plain; charset=utf-8")));

        var file = downloader(wmRuntimeInfo).downloadFile("http://image.example:" + wmRuntimeInfo.getHttpPort() + "/test.txt");
        Assertions.assertEquals("text/plain; charset=utf-8", file.getType());
        Assertions.assertEquals("test.txt", file.getName());
        Assertions.assertEquals("Hello World!".length(), file.getFile().length);
        verify(1, getRequestedFor(urlEqualTo("/test.txt")));
    }

    @Test
    void testFileDownloadNotFound(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(get("/404")
            .willReturn(notFound()));

        var res = downloader(wmRuntimeInfo).downloadFile("http://image.example:" + wmRuntimeInfo.getHttpPort() + "/404");
        Assertions.assertNull(res);
        verify(1, getRequestedFor(urlEqualTo("/404")));
    }
}

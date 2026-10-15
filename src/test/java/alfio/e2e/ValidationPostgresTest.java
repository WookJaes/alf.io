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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Command-boundary failure injection; actual portable PostgreSQL is validated separately. */
class ValidationPostgresTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temporaryDirectory;

    @Test
    void stopCommandThatResistsTerminationCannotBeReplacedByStatus() throws Exception {
        var fixture = runner();
        var lifecycle = new ArrayList<String>();
        var completed = mock(Process.class);
        when(completed.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(completed.isAlive()).thenReturn(false);
        var absent = mock(Process.class);
        when(absent.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(absent.isAlive()).thenReturn(false);
        when(absent.exitValue()).thenReturn(3);
        var stuckStop = mock(Process.class);
        when(stuckStop.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(stuckStop.isAlive()).thenReturn(true);
        when(stuckStop.destroyForcibly()).thenReturn(stuckStop);
        var postgres = new ValidationPostgres((ValidationPostgres.ProcessStarter) command -> {
            assertEquals(fixture.ownedData.toString(), command.get(command.indexOf("-D") + 1));
            if (Path.of(command.getFirst()).getFileName().toString().startsWith("initdb")) {
                Files.createDirectory(fixture.ownedData);
                return completed;
            }
            var action = command.getLast();
            lifecycle.add(action);
            if (action.equals("status") && java.util.Collections.frequency(lifecycle, "status") > 1) {
                return absent;
            }
            return action.equals("stop") ? stuckStop : completed;
        });
        postgres.startPortableServer(temporaryDirectory, fixture.bin);

        assertThrows(IllegalStateException.class, postgres::close);
        assertTrue(Files.isDirectory(fixture.ownedData));
        assertEquals(List.of("start", "status", "stop"), lifecycle,
            "A new status process must never overwrite the still-running stop process");
        verify(stuckStop).waitFor(60_000, TimeUnit.MILLISECONDS);
        verify(stuckStop).waitFor(10, TimeUnit.SECONDS);
        verify(stuckStop).destroyForcibly();
        assertThrows(IllegalStateException.class, postgres::close);
        assertEquals(List.of("start", "status", "stop"), lifecycle);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ALFIO_VALIDATION_POSTGRES_BIN", matches = ".+")
    void actualPostgresChildStopsAfterInjectedStartFailure() throws Exception {
        verifyActualPostgresCleanup(new IOException("Injected pg_ctl start failure after child launch"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ALFIO_VALIDATION_POSTGRES_BIN", matches = ".+")
    void actualPostgresChildStopsAfterInjectedReadinessTimeout() throws Exception {
        verifyActualPostgresCleanup(new IOException("Injected pg_ctl readiness timeout after child launch"));
    }

    private void verifyActualPostgresCleanup(IOException injectedFailure) throws Exception {
        var bin = Path.of(System.getenv("ALFIO_VALIDATION_POSTGRES_BIN")).toAbsolutePath();
        var data = temporaryDirectory.resolve("postgres-data");
        var pgCtl = bin.resolve("pg_ctl" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
        var status = List.of(pgCtl.toString(), "-D", data.toString(), "status");
        var stop = List.of(pgCtl.toString(), "-D", data.toString(), "-m", "fast", "-w", "stop");
        var postgres = new ValidationPostgres((ValidationPostgres.CommandRunner) command -> {
            assertEquals(data.toString(), command.get(command.indexOf("-D") + 1));
            int exit = nativeCommand(command);
            if (command.getLast().equals("start")) {
                assertEquals(0, exit, "Actual PostgreSQL must launch before injecting the command-boundary failure");
                assertEquals(0, nativeCommand(status), "Actual child server must be alive before injection");
                throw injectedFailure;
            }
            return exit;
        });
        try {
            assertSame(injectedFailure, assertThrows(IOException.class,
                () -> postgres.startPortableServer(temporaryDirectory, bin)));
            postgres.close();
            assertEquals(3, nativeCommand(status), "Cleanup must stop the actual owned PostgreSQL server");
        } finally {
            // Never target any other cluster; preserve failed-test files for manual recovery.
            if (Files.exists(data.resolve("PG_VERSION"))) {
                int result = nativeCommand(status);
                if (result == 0) {
                    nativeCommand(stop);
                }
                assertEquals(3, nativeCommand(status), "Owned PostgreSQL could not be stopped; retain test data");
            }
        }
    }

    private static int nativeCommand(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new IOException("Native PostgreSQL regression command timed out");
            }
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("Native PostgreSQL regression command did not terminate");
                }
            }
        }
    }

    @Test
    void childLaunchedBeforeStartFailureIsStillStopped() throws Exception {
        var runner = runner();
        runner.startExit = 1;
        var postgres = new ValidationPostgres(runner);

        assertThrows(IOException.class, () -> postgres.startPortableServer(temporaryDirectory, runner.bin));
        assertTrue(runner.serverRunning, "A failed pg_ctl can leave its postgres child alive");
        postgres.close();

        assertFalse(runner.serverRunning);
        assertEquals(List.of("start", "status", "stop", "status"), runner.lifecycle);
    }

    @Test
    void childLaunchedBeforeStartTimeoutIsStillStopped() throws Exception {
        var runner = runner();
        runner.startTimeout = new IOException("PostgreSQL command timed out: pg_ctl");
        var postgres = new ValidationPostgres(runner);

        assertSame(runner.startTimeout, assertThrows(IOException.class,
            () -> postgres.startPortableServer(temporaryDirectory, runner.bin)));
        assertTrue(runner.serverRunning);
        postgres.close();

        assertFalse(runner.serverRunning);
        assertEquals(List.of("start", "status", "stop", "status"), runner.lifecycle);
    }

    @Test
    void failedStopPreservesDataAndCanBeRetried() throws Exception {
        var runner = runner();
        runner.startExit = 1;
        runner.stopExit = 1;
        var postgres = new ValidationPostgres(runner);
        assertThrows(IOException.class, () -> postgres.startPortableServer(temporaryDirectory, runner.bin));

        assertThrows(IllegalStateException.class, postgres::close);
        assertTrue(runner.serverRunning);
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("postgres-data")));

        runner.stopExit = 0;
        postgres.close();
        assertFalse(runner.serverRunning);
    }

    @Test
    void successfulStopWithoutStoppedStatusCannotAuthorizeDeletion() throws Exception {
        var runner = runner();
        runner.statusUnknown = true;
        var postgres = new ValidationPostgres(runner);
        postgres.startPortableServer(temporaryDirectory, runner.bin);

        assertThrows(IllegalStateException.class, postgres::close);
        assertFalse(runner.serverRunning, "Even a stopped server requires confirmation before deleting its data");
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("postgres-data")));
        assertEquals(List.of("start", "status", "stop", "status"), runner.lifecycle);
    }

    @Test
    void statusTimeoutPreservesDataInsteadOfAssumingServerAbsent() throws Exception {
        var runner = runner();
        var postgres = new ValidationPostgres(runner);
        postgres.startPortableServer(temporaryDirectory, runner.bin);
        runner.statusTimeout = true;

        assertThrows(IllegalStateException.class, postgres::close);
        assertTrue(runner.serverRunning);
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("postgres-data")));
    }

    @Test
    void normalCloseConfirmsStopAndIsIdempotent() throws Exception {
        var runner = runner();
        var postgres = new ValidationPostgres(runner);
        postgres.startPortableServer(temporaryDirectory, runner.bin);

        postgres.close();
        postgres.close();

        assertFalse(runner.serverRunning);
        assertEquals(List.of("start", "status", "stop", "status"), runner.lifecycle);
    }

    @Test
    void alreadyStoppedServerDoesNotNeedStopCommand() throws Exception {
        var runner = runner();
        var postgres = new ValidationPostgres(runner);
        postgres.startPortableServer(temporaryDirectory, runner.bin);
        runner.serverRunning = false;

        postgres.close();

        assertEquals(List.of("start", "status"), runner.lifecycle);
    }

    @Test
    void initFailureDoesNotAttemptToStopAnUnstartedServer() throws Exception {
        var runner = runner();
        runner.initExit = 1;
        var postgres = new ValidationPostgres(runner);
        assertThrows(IOException.class, () -> postgres.startPortableServer(temporaryDirectory, runner.bin));

        postgres.close();

        assertTrue(runner.lifecycle.isEmpty());
    }

    private FakeCommands runner() throws IOException {
        var bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
        var suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        Files.createFile(bin.resolve("initdb" + suffix));
        Files.createFile(bin.resolve("pg_ctl" + suffix));
        return new FakeCommands(bin, temporaryDirectory.resolve("postgres-data"));
    }

    private static final class FakeCommands implements ValidationPostgres.CommandRunner {
        private final Path bin;
        private final Path ownedData;
        private final List<String> lifecycle = new ArrayList<>();
        private boolean serverRunning;
        private int initExit;
        private int startExit;
        private IOException startTimeout;
        private int stopExit;
        private boolean statusUnknown;
        private boolean statusTimeout;

        private FakeCommands(Path bin, Path ownedData) {
            this.bin = bin;
            this.ownedData = ownedData;
        }

        @Override
        public int run(List<String> command) throws Exception {
            assertEquals(ownedData.toString(), command.get(command.indexOf("-D") + 1),
                "Every command must target only this run's owned data directory");
            if (Path.of(command.getFirst()).getFileName().toString().startsWith("initdb")) {
                if (initExit == 0) {
                    Files.createDirectory(ownedData);
                }
                return initExit;
            }
            var action = command.getLast();
            lifecycle.add(action);
            return switch (action) {
                case "start" -> {
                    serverRunning = true;
                    if (startTimeout != null) {
                        throw startTimeout;
                    }
                    yield startExit;
                }
                case "stop" -> {
                    if (stopExit == 0) {
                        serverRunning = false;
                    }
                    yield stopExit;
                }
                case "status" -> {
                    if (statusTimeout) {
                        throw new IOException("PostgreSQL status timed out");
                    }
                    yield statusUnknown ? 4 : serverRunning ? 0 : 3;
                }
                default -> throw new AssertionError("Unexpected command: " + action);
            };
        }
    }
}

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

import alfio.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ALFIO_RUN_E2E", matches="true")
class AttendeeReconciliationE2ETest {
    private static final String A="10000000-0000-0000-0000-000000000001", B="10000000-0000-0000-0000-000000000002",
        C="10000000-0000-0000-0000-000000000003", UNKNOWN="10000000-0000-0000-0000-000000000004";
    private static final String CSV="ID,E-Mail\n"+A+",a@example.invalid\n"+B+",b@example.invalid\n"+B+",different@example.invalid\n"+UNKNOWN+",unknown@example.invalid\n";

    @Test @Timeout(value=5, unit=TimeUnit.MINUTES)
    void comparesReopensGroundsDraftsAndPreservesResultOnFailureWithActualLogins() throws Exception {
        var mode=new AtomicInteger(); var captured=new AtomicReference<String>();
        var mock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool(); mock.setExecutor(executor);
        mock.createContext("/responses", exchange -> {
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); captured.set(body);
            int current=mode.get();
            if (current==2) try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            var payload=Json.OBJECT_MAPPER.readTree(body);
            var facts=Json.OBJECT_MAPPER.readTree(payload.path("input").asString()).path("items");
            List<Map<String,Object>> choices=new ArrayList<>();
            for (var fact: facts) {
                List<String> findings=new ArrayList<>(); fact.path("findings").forEach(f->findings.add(f.asString()));
                choices.add(Map.of("reference",current==3?"F-999":fact.path("reference").asString(),"findingOrder",findings,"style","POLITE","detail","DETAILED"));
            }
            String result=Json.toJson(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",Json.toJson(Map.of("entries",choices)))))),
                "usage",Map.of("input_tokens",120,"output_tokens",60,"input_tokens_details",Map.of("cached_tokens",0))));
            byte[] bytes=(current==1?"synthetic upstream error":result).getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(current==1?500:200,bytes.length);
                if (current==4) {
                    exchange.getResponseBody().write(bytes,0,1);
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(3500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    exchange.getResponseBody().write(bytes,1,bytes.length-1);
                } else {
                    exchange.getResponseBody().write(bytes);
                }
            }
            catch (java.io.IOException ignored) { /* Expected broken connection in the timeout test. */ }
            finally { exchange.close(); }
        }); mock.start();
        Path upload=Files.createTempFile("attendee-synthetic-", ".csv"); Files.writeString(upload,CSV);
        try (var environment=new ValidationEnvironment()) {
            var base=environment.startApp(Path.of(System.getenv("VALIDATION_APP_JAR")),Map.of(
                "ALFIO_ATTENDEEASSISTANT_ENCRYPTIONKEY",Base64.getEncoder().encodeToString(new byte[32]),
                "ALFIO_ATTENDEEASSISTANT_AIENABLED","true","ALFIO_ATTENDEEASSISTANT_OPENAIAPIKEY","synthetic-key",
                "ALFIO_ATTENDEEASSISTANT_TESTENDPOINT","http://127.0.0.1:"+mock.getAddress().getPort()+"/responses",
                "ALFIO_ATTENDEEASSISTANT_TIMEOUTSECONDS","1"));
            environment.seedAdministrator(); environment.assertLoginPageReady();
            var driver=ValidationBrowser.create();
            try {
                var browser=new NormalFlowE2ETest.BrowserWebDriver(NormalFlowE2ETest.BrowserWebDriver.Browser.CHROME,driver);
                var admin=new AdminConsole(browser,base,"admin","validation-only-password",null);
                admin.login();
                String slug="attendee-"+UUID.randomUUID();
                admin.createEvent(new AdminConsole.EventDefinition(slug,"Synthetic attendee event","Synthetic location","Synthetic description",base,base,base,10,"0","CHF","0",true,List.of(),"Standard"));
                seed(environment,slug); admin.logout();
                var owner=new AdminConsole(browser,base,"attendee-owner","validation-only-password",null); owner.login();
                driver.get(base+"/admin#/events/"+slug+"/detail");
                var wait=new WebDriverWait(driver,Duration.ofSeconds(30));
                wait.until(d->d.findElements(By.cssSelector("a[data-ui-sref^='events.single.attendeeReconciliation']")).stream().filter(WebElement::isDisplayed).findFirst().orElse(null)).click();
                wait.until(d->shadow(driver,"#csv-file")!=null);
                shadow(driver,"#csv-file").sendKeys(upload.toAbsolutePath().toString()); click(driver,"#compare");
                wait.until(d->shadow(driver,"#summary")!=null);
                assertTrue(shadow(driver,"#summary").getText().contains("정상 일치 1개 · 확인 필요 3개"));
                assertTrue(shadow(driver,"#summary").getText().contains("시스템 3티켓"));
                assertEquals("ATTENTION",shadow(driver,"#filter").getDomProperty("value"));
                var duplicate=shadow(driver,"[data-reference='F-2']");
                assertTrue(duplicate.getText().contains("CSV 중복 · 이메일 불일치"));
                duplicate.findElement(By.cssSelector("summary")).click();
                assertTrue(duplicate.getText().contains("CSV 레코드 3")); assertTrue(duplicate.getText().contains("CSV 레코드 4"));
                assertTrue(duplicate.getText().contains("different@example.invalid"));
                duplicate.findElement(By.cssSelector("input")).click(); click(driver,"#generate");
                wait.until(d->shadow(driver,"#drafts")!=null);
                assertTrue(shadow(driver,"#drafts").getText().contains("AI가 검증된 표현"));
                assertFalse(captured.get().contains("@example.invalid")); assertFalse(captured.get().contains(A));
                var textarea=shadow(driver,"#draft-F-2"); textarea.clear(); textarea.sendKeys("수정한 합성 안내 초안");
                driver.executeCdpCommand("Browser.grantPermissions",Map.of("origin",base,"permissions",List.of("clipboardReadWrite","clipboardSanitizedWrite")));
                click(driver,"#drafts button");
                wait.until(d->shadow(driver,"#drafts").getText().contains("복사했습니다."));
                Object copied=driver.executeAsyncScript("const done=arguments[0]; navigator.clipboard.readText().then(done,()=>done('clipboard-error'));");
                assertEquals("수정한 합성 안내 초안",copied);
                String id=databaseId(environment);
                assertEncrypted(environment);
                String endpoint="/admin/api/events/"+slug+"/attendee-reconciliations";
                for (int failure:List.of(1,2,3,4)) {
                    mode.set(failure); click(driver,"#generate");
                    wait.until(d->shadow(driver,".error")!=null);
                    String error=shadow(driver,".error").getText();
                    assertTrue(error.contains(failure==1?"AI 연결에 실패":failure==2 || failure==4?"AI 응답 시간이 초과":"근거 검증"),error);
                    assertNotNull(shadow(driver,"#summary")); assertEquals(200,status(driver,endpoint+"/"+id));
                    try (var connection=environment.connection(); var query=connection.prepareStatement("select ai_locked_until from attendee_reconciliation where id=?")) {
                        query.setString(1,id);
                        try (var rows=query.executeQuery()) { assertTrue(rows.next()); assertNull(rows.getObject(1)); }
                    }
                }
                mode.set(0);
                click(driver,"#generate"); wait.until(d->shadow(driver,"#drafts")!=null);
                driver.navigate().refresh(); wait.until(d->shadow(driver,"#history")!=null);
                shadow(driver,"#history").findElements(By.tagName("option")).get(1).click();
                wait.until(d->shadow(driver,"#summary")!=null); assertTrue(shadow(driver,"#summary").getText().contains("확인 필요 3개"));
                click(driver,"#delete-comparison"); wait.until(d->shadow(driver,"#summary")==null);
                assertEquals(404,status(driver,endpoint+"/"+id));
                shadow(driver,"#csv-file").sendKeys(upload.toAbsolutePath().toString()); click(driver,"#compare");
                wait.until(d->shadow(driver,"#summary")!=null); id=databaseId(environment);
                assertEquals(200,status(driver,"/admin/api/reservation/event/"+slug+"/reservations/list"));
                // Required columns and malformed values are corrected in the browser without discarding stored results.
                Files.writeString(upload,"ID,Name\ninvalid,synthetic\n"); shadow(driver,"#csv-file").sendKeys(upload.toAbsolutePath().toString()); click(driver,"#compare");
                wait.until(d->shadow(driver,".error")!=null); assertTrue(shadow(driver,".error").getText().contains("ID와 E-Mail"));
                assertEquals(200,status(driver,endpoint+"/"+id));
                var artifact=System.getProperty("attendee.e2e.artifacts");
                if (artifact!=null) { Files.createDirectories(Path.of(artifact)); Files.copy(driver.getScreenshotAs(OutputType.FILE).toPath(),Path.of(artifact,"comparison.png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                owner.logout(); admin.login();
                assertEquals(404,status(driver,endpoint+"/"+id)); // Same event access does not grant another user's comparison.
                admin.logout(); var outsider=new AdminConsole(browser,base,"attendee-outsider","validation-only-password",null); outsider.login();
                assertEquals(200,status(driver,"/admin/api/events")); assertEquals(403,status(driver,endpoint)); assertEquals(403,status(driver,endpoint+"/"+id));
                outsider.logout(); owner.login();
                // Revoke current membership after creation; persisted ID must not bypass the current access check.
                try (var connection=environment.connection(); var sql=connection.createStatement()) {
                    assertEquals(1,sql.executeUpdate("delete from j_user_organization where user_id=(select id from ba_user where username='attendee-owner')"));
                }
                assertEquals(403,status(driver,endpoint+"/"+id));
                environment.assertNoDataMigrationFailure();
                System.out.println("Attendee E2E: actual owner/admin/outsider logins; 1 matched/3 attention; all finding types; encrypted DB reopen; edited clipboard; AI500/header timeout/body timeout/invalid preserves result; DB lease released and retry succeeds; missing columns; current membership revocation; real model/cost/manual evaluation NOT performed");
            } catch (Exception | AssertionError error) {
                Files.createDirectories(Path.of("build", "validation"));
                Files.writeString(Path.of("build", "validation", "attendee-failure.html"), driver.getPageSource()
                    + driver.executeScript("return document.querySelector('alfio-attendee-reconciliation')?.shadowRoot?.innerHTML ?? ''"));
                Files.copy(driver.getScreenshotAs(OutputType.FILE).toPath(), Path.of("build", "validation", "attendee-failure.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                throw error;
            } finally { driver.quit(); }
        } finally { mock.stop(0); executor.shutdownNow(); Files.deleteIfExists(upload); }
    }
    private static void click(ChromeDriver driver, String selector) {
        WebElement element = shadow(driver, selector);
        driver.executeScript("arguments[0].scrollIntoView({block:'center'})", element);
        element.click();
    }
    private static WebElement shadow(ChromeDriver driver,String selector) {
        return (WebElement)driver.executeScript("return document.querySelector('alfio-attendee-reconciliation')?.shadowRoot?.querySelector(arguments[0]) ?? null",selector);
    }
    private static int status(ChromeDriver driver,String path) {
        Object response=driver.executeAsyncScript("const done=arguments[arguments.length-1]; fetch(arguments[0],{credentials:'include',cache:'no-store'}).then(r=>done(r.status),()=>done(-1));",path);
        return ((Number)response).intValue();
    }
    private static String databaseId(ValidationEnvironment environment) throws Exception {
        try(var connection=environment.connection();var sql=connection.createStatement();var rows=sql.executeQuery("select id from attendee_reconciliation")) {
            assertTrue(rows.next());String id=rows.getString(1);assertFalse(rows.next());return id;
        }
    }
    private static void assertEncrypted(ValidationEnvironment environment) throws Exception {
        try(var connection=environment.connection();var sql=connection.createStatement();var rows=sql.executeQuery("select encrypted_result,summary_json,extract(epoch from expires_at-created_at) from attendee_reconciliation")) {
            assertTrue(rows.next());assertFalse(rows.getString(1).contains("@example.invalid"));assertFalse(rows.getString(1).contains(A));
            assertFalse(rows.getString(2).contains("@"));assertEquals(86400,rows.getDouble(3),.01);
        }
    }
    private static void seed(ValidationEnvironment environment,String slug) throws Exception {
        try(var connection=environment.connection();var sql=connection.createStatement()) {
            connection.setAutoCommit(false);
            int eventId,orgId;
            try(var q=connection.prepareStatement("select id,org_id from event where short_name=?")) {
                q.setString(1,slug);try(var rows=q.executeQuery()){assertTrue(rows.next());eventId=rows.getInt(1);orgId=rows.getInt(2);}
            }
            sql.executeUpdate("insert into organization(name,description,email,slug) values('Synthetic outsider','Fixture','out@example.invalid','attendee-other')");
            for(String user:List.of("attendee-owner","attendee-outsider")) {
                try(var q=connection.prepareStatement("insert into ba_user(username,password,first_name,last_name,email_address,enabled) values(?,?,'Synthetic','Organizer',?,true)")) {
                    q.setString(1,user);q.setString(2,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("validation-only-password"));q.setString(3,user+"@example.invalid");assertEquals(1,q.executeUpdate());
                }
                sql.executeUpdate("insert into authority(username,role) values('"+user+"','ROLE_OWNER')");
                String org=user.equals("attendee-owner")?String.valueOf(orgId):"(select id from organization where slug='attendee-other')";
                sql.executeUpdate("insert into j_user_organization(user_id,org_id) select id,"+org+" from ba_user where username='"+user+"'");
            }
            String reservation=UUID.randomUUID().toString();
            try(var q=connection.prepareStatement("insert into tickets_reservation(id,validity,status,event_id_fk,organization_id_fk,confirmation_ts) values(?,now()+interval '1 day','COMPLETE',?,?,now())")) {
                q.setString(1,reservation);q.setInt(2,eventId);q.setInt(3,orgId);assertEquals(1,q.executeUpdate());
            }
            List<Integer> tickets=new ArrayList<>();
            try(var rows=sql.executeQuery("select id from ticket where event_id="+eventId+" order by id limit 3")){while(rows.next())tickets.add(rows.getInt(1));}
            assertEquals(3,tickets.size());
            for(int i=0;i<3;i++)try(var q=connection.prepareStatement("update ticket set uuid=?,email_address=?,status=?,tickets_reservation_id=? where id=?")) {
                q.setString(1,List.of(A,B,C).get(i));q.setString(2,List.of("a@example.invalid","b@example.invalid","c@example.invalid").get(i));
                q.setString(3,List.of("ACQUIRED","CHECKED_IN","TO_BE_PAID").get(i));q.setString(4,reservation);q.setInt(5,tickets.get(i));assertEquals(1,q.executeUpdate());
            }
            connection.commit();
        }
    }
}

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

import alfio.controller.api.ApiControllerExceptionHandler;
import alfio.manager.AccessService;
import alfio.manager.attendee.AttendeeReconciliationManager;
import alfio.manager.attendee.ReconciliationException;
import alfio.manager.support.AccessDeniedException;
import alfio.model.EventAndOrganizationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttendeeReconciliationApiControllerTest {
    private final AccessService access = mock(AccessService.class);
    private final AttendeeReconciliationManager manager = mock(AttendeeReconciliationManager.class);
    private MockMvc mvc;
    private final String url = "/admin/api/events/example/attendee-reconciliations";
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AttendeeReconciliationApiController(access,manager))
            .setControllerAdvice(new ApiControllerExceptionHandler()).build();
        when(access.checkEventOwnership(any(),eq("example"))).thenReturn(new EventAndOrganizationId(42,7));
    }
    @Test void rejectsForbiddenBeforeComparisonHistoryDetailDraftOrDelete() throws Exception {
        doThrow(new AccessDeniedException()).when(access).checkEventOwnership(any(),eq("example"));
        mvc.perform(post(url).principal(()->"outsider").contentType("text/csv").content("private-csv")).andExpect(status().isForbidden());
        mvc.perform(get(url).principal(()->"outsider")).andExpect(status().isForbidden());
        mvc.perform(get(url+"/id").principal(()->"outsider")).andExpect(status().isForbidden());
        mvc.perform(post(url+"/id/drafts").principal(()->"outsider").contentType("application/json").content("{\"references\":[\"F-1\"]}")).andExpect(status().isForbidden());
        mvc.perform(delete(url+"/id").principal(()->"outsider")).andExpect(status().isForbidden());
        verifyNoInteractions(manager);
    }
    @Test void returnsSafeRowErrorAndDisablesResponseCaching() throws Exception {
        when(manager.compare(eq(42),eq("owner"),any())).thenThrow(new ReconciliationException(HttpStatus.BAD_REQUEST,"INVALID_ID",2));
        mvc.perform(post(url).principal(()->"owner").contentType("text/csv").content("private-cell"))
            .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value("INVALID_ID")).andExpect(jsonPath("$.rowNumber").value(2));
    }
    @Test void enforcesUploadLimitBeforeReadingOrCallingManager() throws Exception {
        mvc.perform(post(url).principal(()->"owner").contentType("text/csv").content(new byte[5*1024*1024+1]))
            .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        verifyNoInteractions(manager);
    }
}

package it.ristomanager.cloud.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import it.ristomanager.cloud.support.ScriptedLlm;
import it.ristomanager.cloud.support.ScriptedLlmConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The AI status endpoint: who may ask, and that it reports whether the model answers without ever failing. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ScriptedLlmConfig.class})
class AiStatusIT {

    private static final String STATUS = "/api/v1/ai/status";

    @Autowired
    MockMvc mvc;

    @Autowired
    ScriptedLlm llm;

    ApiClient api;
    Actor owner;

    @BeforeEach
    void setUp() throws Exception {
        llm.reset();
        api = new ApiClient(mvc);
        owner = api.signup("R");
    }

    @Test
    void ownersAndManagersSeeWhetherTheModelIsSwitchedOnAndAnswering() throws Exception {
        Actor manager = api.staff(owner, "MANAGER");

        for (Actor who : new Actor[] {owner, manager}) {
            api.get(who, STATUS).andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.reachable").value(true))
                    .andExpect(jsonPath("$.model").value("scripted"));
        }
    }

    @Test
    void anUnreachableModelIsReportedNotAnError() throws Exception {
        llm.reachable = false;

        api.get(owner, STATUS).andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.model").value("scripted"));
    }

    @Test
    void waitersKitchenAndAnonymousVisitorsAreRefused() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        Actor kitchen = api.staff(owner, "KITCHEN");

        api.get(waiter, STATUS).andExpect(status().isForbidden());
        api.get(kitchen, STATUS).andExpect(status().isForbidden());
        mvc.perform(get(STATUS)).andExpect(status().isUnauthorized());
    }
}

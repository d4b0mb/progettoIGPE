package it.ristomanager.cloud.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Small helper so integration tests read like the API calls they make. */
public class ApiClient {

    public static final String PASSWORD = "correct-horse-battery";

    /** A signed-in user of some restaurant. */
    public record Actor(String token, String userId, String restaurantId, String email) {
    }

    private final MockMvc mvc;

    public ApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Actor signup(String restaurantName) throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantName":"%s","ownerName":"Marco","email":"%s","password":"%s"}
                                """.formatted(restaurantName, email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        return actorOf(res, email);
    }

    /** The owner creates a staff member, who then logs in. */
    public Actor staff(Actor owner, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        send(owner, MockMvcRequestBuilders.post("/api/v1/staff"), """
                {"displayName":"Staff","email":"%s","password":"%s","role":"%s"}
                """.formatted(email, PASSWORD, role)).andExpect(status().isCreated());
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return actorOf(res, email);
    }

    private static Actor actorOf(MvcResult res, String email) throws Exception {
        String body = res.getResponse().getContentAsString();
        return new Actor(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.user.id"),
                JsonPath.read(body, "$.user.restaurantId"), email);
    }

    /** Signs in with an email and password; the caller checks the status. */
    public ResultActions login(String email, String password) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    /** Calls an endpoint that needs no sign-in. */
    public ResultActions anonymousPost(String url, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions get(Actor who, String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + who.token()));
    }

    public ResultActions post(Actor who, String url, String json) throws Exception {
        return send(who, MockMvcRequestBuilders.post(url), json);
    }

    public ResultActions put(Actor who, String url, String json) throws Exception {
        return send(who, MockMvcRequestBuilders.put(url), json);
    }

    public ResultActions patch(Actor who, String url, String json) throws Exception {
        return send(who, MockMvcRequestBuilders.patch(url), json);
    }

    public ResultActions delete(Actor who, String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete(url).header("Authorization", "Bearer " + who.token()));
    }

    private ResultActions send(Actor who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                               String json) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + who.token())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }
}

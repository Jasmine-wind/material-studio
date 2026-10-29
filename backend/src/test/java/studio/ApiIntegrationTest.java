package studio;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StudioMapper mapper;
    @Autowired Storage storage;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
    String upload(String owner) throws Exception {
        var result = mvc.perform(multipart("/api/assets").file(new MockMultipartFile("file", "../../test.png", "image/png", png()))
                .header("X-Demo-User", owner)).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
    String create(String owner, String template, List<String> assets, String stage, int delay, int status) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("templateId", template); body.put("assetIds", assets);
        var simulation = new LinkedHashMap<String,Object>(); simulation.put("failStage", stage); simulation.put("delayMs", delay);
        body.put("simulation", simulation);
        var response = mvc.perform(post("/api/tasks").header("X-Demo-User",owner).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andReturn().getResponse();
        if (status != 202) return null;
        var tree = json.readTree(response.getContentAsString());
        assertThat(tree.get("status").asText()).isEqualTo("QUEUED");
        assertThat(tree.get("stage").asText()).isEqualTo("NONE");
        return tree.get("id").asText();
    }
    Models.Task await(String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            var task = mapper.task(id, "alice");
            if (Set.of("SUCCEEDED", "FAILED").contains(task.status)) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("Task timed out");
    }
    @Test void templatesIdentityAnd404() throws Exception {
        mvc.perform(get("/api/templates")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/templates").header("X-Demo-User", "mallory")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/templates").header("X-Demo-User", "alice")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        mvc.perform(get("/api/templates/nope").header("X-Demo-User", "alice")).andExpect(status().isNotFound());
        mvc.perform(get("/api/templates?category=unknown").header("X-Demo-User", "alice")).andExpect(content().json("[]"));
    }
    @Test void acceptedQuicklySuccessReadOnlyAndIsolation() throws Exception {
        String asset = upload("alice");
        long start = System.nanoTime();
        String id = create("alice","lunar", List.of(asset),null,300,202);
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(700);
        mvc.perform(get("/api/tasks/"+id+"/result").header("X-Demo-User","alice")).andExpect(status().isConflict());
        var terminal = await(id);
        assertThat(terminal.status).isEqualTo("SUCCEEDED");
        assertThat(terminal.stage).isEqualTo("FINALIZE");
        assertThat(terminal.finishedAt).isNotNull();
        mvc.perform(get("/api/tasks/"+id+"/result").header("X-Demo-User","alice")).andExpect(status().isOk()).andExpect(content().bytes(png()));
        for (int i=0;i<3;i++) mvc.perform(get("/api/tasks/"+id).header("X-Demo-User","alice")).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(mapper.startStage(id,"FINALIZE","CHECK_MATERIAL","RUNNING")).isZero();
        assertThat(mapper.fail(id,"GENERATE","late failure")).isZero();
        for (String path : List.of("/api/tasks/"+id,"/api/tasks/"+id+"/result","/api/assets/"+asset+"/content"))
            mvc.perform(get(path).header("X-Demo-User","bob")).andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/"+asset+"/content").header("X-Demo-User","alice")).andExpect(content().bytes(png()));
    }
    @ParameterizedTest @ValueSource(strings={"CHECK_MATERIAL","GENERATE","FINALIZE"})
    void deterministicFailure(String stage) throws Exception {
        String id = create("alice","lunar",List.of(upload("alice")),stage,0,202);
        var task = await(id);
        assertThat(task.status).isEqualTo("FAILED"); assertThat(task.failedStage).isEqualTo(stage);
        assertThat(task.stage).isEqualTo(stage); assertThat(task.errorMessage).contains(stage); assertThat(task.finishedAt).isNotNull();
        assertThat(task.resultStorageKey).isNull();
        assertThat(mapper.succeed(id,"late.png")).isZero();
    }
    @Test void invalidInputs() throws Exception {
        String asset = upload("alice");
        create("alice","unknown",List.of(asset),null,0,404);
        create("alice","dune",List.of(asset),null,0,400);
        create("alice","dune",List.of(asset,asset),null,0,400);
        create("bob","lunar",List.of(asset),null,0,404);
        create("alice","lunar",List.of(asset),"UNKNOWN",0,400);
        create("alice","lunar",List.of(asset),null,10001,400);
        create("alice","lunar",List.of(asset),null,-1,400);
        FilesDelete(asset);
        create("alice","lunar",List.of(asset),null,0,400);
    }
    void FilesDelete(String asset) throws IOException { storage.removeUpload(mapper.asset(asset,"alice").storageKey); }
    @Test void revalidatesStoredMimeAndSizeAndAcceptsJpeg() throws Exception {
        String asset = upload("alice");
        jdbc.update("UPDATE assets SET mime_type='text/plain' WHERE id=?", asset);
        create("alice","lunar",List.of(asset),null,0,400);
        jdbc.update("UPDATE assets SET mime_type='image/png',size_bytes=? WHERE id=?",Storage.MAX_BYTES+1,asset);
        create("alice","lunar",List.of(asset),null,0,400);
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"jpeg",out);
        mvc.perform(multipart("/api/assets").file(new MockMultipartFile("file","sample.jpg","image/jpeg",out.toByteArray()))
                .header("X-Demo-User","bob")).andExpect(status().isCreated()).andExpect(jsonPath("$.mimeType").value("image/jpeg"));
        mvc.perform(get("/api/unknown").header("X-Demo-User","alice")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void uploadValidation() throws Exception {
        for (var file : List.of(new MockMultipartFile("file","bad.png","image/png","not image".getBytes()),
                new MockMultipartFile("file","bad.jpg","image/jpeg",png()),
                new MockMultipartFile("file","empty.png","image/png",new byte[0]),
                new MockMultipartFile("file","huge.png","image/png",new byte[(int)Storage.MAX_BYTES+1]))) {
            mvc.perform(multipart("/api/assets").file(file).header("X-Demo-User","alice"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }
}

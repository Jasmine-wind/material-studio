package studio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.List;
import static studio.Models.*;

@Service
public class Templates {
    private final List<Template> templates;
    public Templates(ObjectMapper mapper) throws IOException {
        try (var in = new ClassPathResource("templates.json").getInputStream()) {
            templates = List.copyOf(mapper.readValue(in, new TypeReference<List<Template>>() {}));
        }
    }
    public List<Template> list(String category) {
        return templates.stream().filter(t -> category == null || category.isBlank() || category.equals(t.category())).toList();
    }
    public Template get(String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(ApiError::missing);
    }
}

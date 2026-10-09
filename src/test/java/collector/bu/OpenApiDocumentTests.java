package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiDocumentTests {
    /**
     * 验证可下载 JSON 文档与生成接口代码的 YAML 定义完全一致，防止文档与代码脱节。
     */
    @Test
    void downloadableJsonMatchesTheApiUsedForCodeGeneration() throws Exception {
        var mapper = new ObjectMapper();
        try (var input = Files.newInputStream(Path.of("src/main/resources/api.yaml"))) {
            Object specification = new Yaml().load(input);
            var document = mapper.readTree(Path.of("docs/smart-finance-openapi.json").toFile());
            assertThat(document).isEqualTo(mapper.valueToTree(specification));
        }
    }
}

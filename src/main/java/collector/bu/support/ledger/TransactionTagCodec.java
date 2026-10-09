package collector.bu.support.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** 交易标签的共享编解码器，不执行SQL，供流水和报表DAO复用。 */
@Component
public class TransactionTagCodec {
    private final ObjectMapper mapper;
    /** 注入JSON序列化器，统一标签持久化格式。 */
    public TransactionTagCodec(ObjectMapper mapper) { this.mapper=mapper; }
    /** 统一排序和去重标签，保存与查重使用同一 JSON，标签顺序不改变重复判断。 */
    public List<String> normalizeTags(List<String> tags) { return tags==null ? List.of() : tags.stream().map(String::strip).distinct().sorted().toList(); }
    /** 将规范标签编码为可移植 JSON 文本，不依赖数据库专用 JSON 语法。 */
    public String encodeTags(List<String> tags) { try { return mapper.writeValueAsString(normalizeTags(tags)); } catch(java.io.IOException e) { throw new IllegalStateException("Tag encoding failed"); } }
    /** 解析数据库标签列表，损坏数据不会被静默忽略。 */
    public List<String> decodeTags(String json) { try { return mapper.readValue(json,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}); } catch(java.io.IOException e) { throw new IllegalStateException("Tag data invalid"); } }
}

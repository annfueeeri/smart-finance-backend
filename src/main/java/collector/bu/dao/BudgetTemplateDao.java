package collector.bu.dao;

import collector.bu.exception.BudgetException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import static collector.bu.model.BudgetModels.*;

/** 预算模板模块 SQL：方案序列化、保存、列表及归属查询。 */
@Repository
public class BudgetTemplateDao {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<Template> template;
    /** 注入JDBC和JSON编解码器，初始化模板行映射。 */
    public BudgetTemplateDao(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;this.mapper=mapper;
        template=(r,n) -> new Template(r.getLong("id"),r.getString("name"),decode(r.getString("items_json")),
                r.getTimestamp("created_at").toLocalDateTime(),r.getString("created_by"),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("updated_by"),r.getBoolean("is_deleted"));
    }

    /** 将模板配置序列化为内部 JSON，仅由业务层验证后的字段生成。 */
    private String encode(List<TemplateItem> items) { try { return mapper.writeValueAsString(items); } catch(java.io.IOException e) { throw new IllegalStateException("Template serialization failed"); } }
    /** 解码数据库模板，损坏配置不会被静默当作空模板应用。 */
    private List<TemplateItem> decode(String json) { try { return mapper.readValue(json,new TypeReference<List<TemplateItem>>(){}); } catch(java.io.IOException e) { throw new IllegalStateException("Template data invalid"); } }
    /** 保存本人常用方案，名称唯一，创建审计由后端填写。 */
    public Template saveTemplate(long userId,String actor,String name,List<TemplateItem> items) {
        jdbc.update("INSERT INTO budget_template(user_id,name,items_json,created_by,updated_by) VALUES(?,?,?,?,?)",userId,name.strip(),encode(items),actor,actor);
        return jdbc.query("SELECT * FROM budget_template WHERE user_id=? AND name=?",template,userId,name.strip()).get(0);
    }
    /** 返回本人未删除模板及完整配置，不返回其他人的方案。 */
    public List<Template> templates(long userId) { return jdbc.query("SELECT * FROM budget_template WHERE user_id=? AND is_deleted=FALSE ORDER BY id",template,userId); }
    /** 查找本人模板以应用到新月份，不能借用其他用户的模板 ID。 */
    public Template template(long userId,long id) { return templates(userId).stream().filter(t -> t.id()==id).findFirst().orElseThrow(() -> new BudgetException("BUDGET_NOT_FOUND")); }
}

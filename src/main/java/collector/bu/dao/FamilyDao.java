package collector.bu.dao;

import static collector.bu.model.FamilyModels.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 家庭及成员 SQL；唯一有效成员约束保证一个账号最多加入一个家庭。 */
@Repository
public class FamilyDao {
    private final JdbcTemplate jdbc;
    /** 注入家庭数据库访问组件。 */
    public FamilyDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** 查询当前启用的家庭成员关系，无成员关系时返回 null。 */
    public Long membership(long userId) {
        return jdbc.queryForList("SELECT m.family_id FROM family_member m JOIN family_group f ON f.id=m.family_id WHERE m.active_user_id=? AND m.is_deleted=FALSE AND f.is_deleted=FALSE",Long.class,userId).stream().findFirst().orElse(null);
    }
    /** 只在邀请码哈希匹配且家庭未删除时返回家庭主键。 */
    public Long invitation(String hash) {
        return jdbc.queryForList("SELECT id FROM family_group WHERE invitation_hash=? AND is_deleted=FALSE",Long.class,hash).stream().findFirst().orElse(null);
    }
    /** 新增家庭并保存邀请码哈希；原始邀请码不写入数据库。 */
    public long create(long owner,Create input,String hash,String actor) {
        var keys=new GeneratedKeyHolder();
        jdbc.update(c->{var s=c.prepareStatement("INSERT INTO family_group(name,currency,timezone,owner_user_id,invitation_hash,created_by,updated_by) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});
            s.setString(1,input.name().strip());s.setString(2,input.currency());s.setString(3,input.timezone());s.setLong(4,owner);s.setString(5,hash);s.setString(6,actor);s.setString(7,actor);return s;},keys);
        return keys.getKey().longValue();
    }
    /** 加入成员并保存独立审计记录，退出后再次加入不会覆盖旧记录。 */
    public void add(long familyId,long userId,String actor) {
        jdbc.update("INSERT INTO family_member(family_id,user_id,active_user_id,created_by,updated_by) VALUES(?,?,?,?,?)",familyId,userId,userId,actor,actor);
    }
    /** 查询家庭及可公开的成员资料，家庭身份从 owner_user_id 计算。 */
    public Family family(long id) {
        return jdbc.queryForObject("SELECT * FROM family_group WHERE id=? AND is_deleted=FALSE",(rs,row)->new Family(rs.getLong("id"),rs.getString("name"),rs.getString("currency"),rs.getString("timezone"),rs.getLong("owner_user_id"),members(id),rs.getTimestamp("created_at").toLocalDateTime(),rs.getString("created_by"),rs.getTimestamp("updated_at").toLocalDateTime(),rs.getString("updated_by"),rs.getBoolean("is_deleted")),id);
    }
    /** 列出家庭的未删除成员，禁用账号仍展示供家庭管理员移除，但没有记账权限。 */
    public List<Member> members(long id) {
        return jdbc.query("SELECT m.*,u.username,u.display_name,CASE WHEN f.owner_user_id=u.id THEN 'OWNER' ELSE 'MEMBER' END family_role FROM family_member m JOIN app_user u ON u.id=m.user_id JOIN family_group f ON f.id=m.family_id WHERE m.family_id=? AND m.is_deleted=FALSE ORDER BY m.id",
                (rs,row)->new Member(rs.getLong("user_id"),rs.getString("username"),rs.getString("display_name"),rs.getString("family_role"),rs.getTimestamp("created_at").toLocalDateTime(),rs.getString("created_by"),rs.getTimestamp("updated_at").toLocalDateTime(),rs.getString("updated_by"),rs.getBoolean("is_deleted")),id);
    }
    /** 逻辑移除成员并释放唯一有效成员键，历史家庭流水及操作者信息保留。 */
    public void remove(long familyId,long userId,String actor) {
        jdbc.update("UPDATE family_member SET is_deleted=TRUE,active_user_id=NULL,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE family_id=? AND user_id=? AND is_deleted=FALSE",actor,familyId,userId);
    }
    /** 轮换邀请码哈希并记录操作者，旧邀请凭据立即失效。 */
    public void rotate(long id,String hash,String actor) { jdbc.update("UPDATE family_group SET invitation_hash=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE id=?",hash,actor,id); }
    /** 移交家庭管理权，与系统用户角色无关。 */
    public void owner(long id,long userId,String actor) { jdbc.update("UPDATE family_group SET owner_user_id=?,updated_at=CURRENT_TIMESTAMP(6),updated_by=? WHERE id=?",userId,actor,id); }
}

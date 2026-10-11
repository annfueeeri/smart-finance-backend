package collector.bu.service.impl;

import collector.bu.dao.*;
import collector.bu.entity.UserAccount;
import collector.bu.exception.FamilyException;
import collector.bu.service.FamilyService;
import static collector.bu.model.FamilyModels.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用高熵邀请码加入家庭，成员变更与财务操作使用相同账本锁。 */
@Service
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class FamilyServiceImpl implements FamilyService {
    private final UserDao users;
    private final FamilyDao families;
    private final BookDao books;
    private final SecureRandom random=new SecureRandom();
    /** 注入账号、家庭和账本的独立 DAO。 */
    public FamilyServiceImpl(UserDao users,FamilyDao families,BookDao books) { this.users=users;this.families=families;this.books=books; }
    /** 复查当前账号有效性，不使用客户端传入的操作者身份。 */
    private UserAccount actor(String username) { return users.findByUsername(username).filter(u->u.enabled() && !u.deleted()).orElseThrow(()->new AccessDeniedException("Active account required")); }
    /** 生成 256 位随机凭据，邀请码不包含可预测的家庭或用户 ID。 */
    private String code() { byte[] value=new byte[32];random.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    /** 计算邀请码哈希用于数据库查找，不存储原始邀请凭据。 */
    private String hash(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 验证本人仍在家庭内并锁定家庭账本。 */
    private long family(UserAccount user) { var id=families.membership(user.id());if(id==null)throw new FamilyException("FAMILY_NOT_FOUND");books.lock(-id);return id; }
    /** 检查当前操作者是否为家庭管理员，系统 ADMIN 身份不提供家庭管理权。 */
    private void requireOwner(long id,long userId) { if(families.family(id).ownerUserId()!=userId)throw new AccessDeniedException("Family owner required"); }
    /** 返回本人可见的家庭数据及当前选择。 */
    @Override
    public Status status(String username) { var user=actor(username);users.lockUser(user.id());var id=families.membership(user.id());if(id!=null)books.lock(-id);return new Status(books.scope(user.id()),id==null ? null : families.family(id)); }
    /** 创建空的家庭共享账本，原个人数据始终保留在个人账本。 */
    @Override
    public Invitation create(String username,Create input) {
        var user=actor(username);users.lockUser(user.id());
        if(families.membership(user.id())!=null)throw new FamilyException("ALREADY_IN_FAMILY");
        try { var c=Currency.getInstance(input.currency());if(c.getDefaultFractionDigits()<0 || c.getDefaultFractionDigits()>4 || input.name().strip().isEmpty() || input.name().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();ZoneId.of(input.timezone()); }
        catch(IllegalArgumentException | java.time.DateTimeException e) { throw new FamilyException("INVALID_FAMILY"); }
        var code=code();var id=families.create(user.id(),input,hash(code),username);books.createFamily(id,input.currency(),input.timezone(),username);families.add(id,user.id(),username);books.scope(user.id(),"FAMILY",username);
        return new Invitation(status(username),code);
    }
    /** 验证邀请、家庭容量和唯一有效成员资格，持锁后再次检查邀请，防止与轮换竞态。 */
    @Override
    public Status join(String username,Join input) {
        var user=actor(username);users.lockUser(user.id());
        if(families.membership(user.id())!=null)throw new FamilyException("ALREADY_IN_FAMILY");
        var hash=hash(input.invitationCode().strip());var id=families.invitation(hash);if(id==null)throw new FamilyException("INVALID_INVITATION");
        books.lock(-id);if(!Objects.equals(id,families.invitation(hash)))throw new FamilyException("INVALID_INVITATION");
        if(families.members(id).size()>=50)throw new FamilyException("FAMILY_FULL");
        families.add(id,user.id(),username);books.scope(user.id(),"FAMILY",username);return status(username);
    }
    /** 切换账本并记录审计数据，未加入家庭的账号不能选择 FAMILY。 */
    @Override
    public Status scope(String username,Scope input) {
        var user=actor(username);users.lockUser(user.id());
        if(!Set.of("PERSONAL","FAMILY").contains(input.scope()))throw new FamilyException("INVALID_FAMILY");
        if(input.scope().equals("FAMILY"))family(user);
        books.scope(user.id(),input.scope(),username);return status(username);
    }
    /** 为家庭管理员轮换邀请凭据，只有这次响应公开新邀请码。 */
    @Override
    public Invitation rotate(String username) {
        var user=actor(username);users.lockUser(user.id());var id=family(user);requireOwner(id,user.id());var code=code();families.rotate(id,hash(code),username);return new Invitation(status(username),code);
    }
    /** 原子撤销成员资格并恢复其个人选择，已有登录会话立即失去家庭访问能力。 */
    @Override
    public Status remove(String username,long userId) {
        var user=actor(username);var ownFamily=families.membership(user.id());
        if(ownFamily==null)throw new FamilyException("FAMILY_NOT_FOUND");
        // 先检查管理权限，再查询目标，防止无权账号枚举其他人的家庭成员关系。
        if(user.id()!=userId)requireOwner(ownFamily,user.id());
        if(!Objects.equals(families.membership(userId),ownFamily))throw new FamilyException("FAMILY_MEMBER_NOT_FOUND");
        users.lockUser(userId);var id=family(user);
        if(user.id()!=userId)requireOwner(id,user.id());
        if(!Objects.equals(families.membership(userId),id))throw new FamilyException("FAMILY_MEMBER_NOT_FOUND");
        if(families.family(id).ownerUserId()==userId)throw new FamilyException("FAMILY_OWNER_REQUIRED");
        families.remove(id,userId,username);books.scope(userId,"PERSONAL",username);
        // 此处不额外请求操作者用户锁，避免与切换账本的请求形成锁循环。
        var own=families.membership(user.id());return new Status(books.scope(user.id()),own==null ? null : families.family(own));
    }
    /** 向启用的家庭成员移交管理权，成员资格与共享财务数据保持不变。 */
    @Override
    public Status owner(String username,Owner input) {
        var user=actor(username);users.lockUser(user.id());var id=family(user);requireOwner(id,user.id());
        if(!Objects.equals(families.membership(input.userId()),id) || users.findById(input.userId()).filter(u->u.enabled() && !u.deleted()).isEmpty())throw new FamilyException("FAMILY_MEMBER_NOT_FOUND");
        families.owner(id,input.userId(),username);return status(username);
    }
}

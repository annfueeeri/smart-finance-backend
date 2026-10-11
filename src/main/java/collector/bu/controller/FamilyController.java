package collector.bu.controller;

import collector.bu.service.FamilyService;
import static collector.bu.model.FamilyModels.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 家庭组路由，操作者始终来自已经认证的会话；写操作由安全过滤器校验 CSRF。 */
@RestController
@RequestMapping("/api/family")
public class FamilyController {
    private final FamilyService service;
    /** 注入家庭业务服务。 */
    public FamilyController(FamilyService service) { this.service=service; }
    /** GET /api/family：返回当前账本选择和本人家庭的成员信息。 */
    @GetMapping
    public Status status(Authentication auth) { return service.status(auth.getName()); }
    /** POST /api/family：创建独立共享账本并返回一次性邀请码。 */
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Invitation create(Authentication auth,@Valid @RequestBody Create input) { return service.create(auth.getName(),input); }
    /** POST /api/family/join：校验邀请码，主动加入家庭并启用共享账本。 */
    @PostMapping("/join")
    public Status join(Authentication auth,@Valid @RequestBody Join input) { return service.join(auth.getName(),input); }
    /** PUT /api/family/scope：切换个人与家庭账本，禁止访问其他家庭。 */
    @PutMapping("/scope")
    public Status scope(Authentication auth,@Valid @RequestBody Scope input) { return service.scope(auth.getName(),input); }
    /** POST /api/family/invitation：家庭管理员轮换邀请码，旧邀请码失效。 */
    @PostMapping("/invitation")
    public Invitation rotate(Authentication auth) { return service.rotate(auth.getName()); }
    /** DELETE /api/family/members/{userId}：成员退出本人，或管理员移除其他成员。 */
    @DeleteMapping("/members/{userId}")
    public Status remove(Authentication auth,@PathVariable("userId") long userId) { return service.remove(auth.getName(),userId); }
    /** PUT /api/family/owner：家庭管理员向本家庭启用成员移交管理权。 */
    @PutMapping("/owner")
    public Status owner(Authentication auth,@Valid @RequestBody Owner input) { return service.owner(auth.getName(),input); }
}

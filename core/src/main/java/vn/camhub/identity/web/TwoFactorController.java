package vn.camhub.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.identity.application.TwoFactorService;
import vn.camhub.identity.application.UserService;
import vn.camhub.platform.security.CurrentUser;

/** 2FA trong hồ sơ của chính người dùng, và Admin đặt lại 2FA khi người dùng mất thiết bị (US-31). */
@RestController
@RequestMapping("/api/v1")
public class TwoFactorController {

    public record CodeRequest(@NotBlank String code) {
    }

    public record RecoveryCodes(List<String> recoveryCodes) {
    }

    private final TwoFactorService twoFactor;
    private final UserService users;

    public TwoFactorController(TwoFactorService twoFactor, UserService users) {
        this.twoFactor = twoFactor;
        this.users = users;
    }

    @GetMapping("/me/2fa")
    public Map<String, Object> status() {
        return twoFactor.status(me());
    }

    @PostMapping("/me/2fa/setup")
    public Map<String, String> setup() {
        return twoFactor.beginSelfSetup(me());
    }

    @PostMapping("/me/2fa/enable")
    public RecoveryCodes enable(@Valid @RequestBody CodeRequest req) {
        return new RecoveryCodes(twoFactor.confirmSelfSetup(me(), req.code()));
    }

    @PostMapping("/me/2fa/recovery-codes")
    public RecoveryCodes regenerate(@Valid @RequestBody CodeRequest req) {
        return new RecoveryCodes(twoFactor.regenerateRecoveryCodes(me(), req.code()));
    }

    @PostMapping("/me/2fa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@Valid @RequestBody CodeRequest req) {
        twoFactor.disable(me(), req.code());
    }

    @PostMapping("/users/{id}/2fa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public void reset(@PathVariable UUID id) {
        users.get(me(), id); // kiểm tra người dùng nằm trong phạm vi quản lý
        twoFactor.reset(me(), id);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}

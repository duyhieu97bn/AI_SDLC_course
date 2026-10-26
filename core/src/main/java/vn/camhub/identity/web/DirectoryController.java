package vn.camhub.identity.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.identity.application.DirectoryService;
import vn.camhub.identity.application.DirectoryService.ConnectionView;
import vn.camhub.identity.application.DirectoryService.SyncResult;
import vn.camhub.identity.infrastructure.DirectoryRepository.Connection;
import vn.camhub.identity.infrastructure.DirectoryRepository.Mapping;
import vn.camhub.platform.security.CurrentUser;

/** Kết nối LDAP/AD và ánh xạ nhóm (US-44, design/users.html tab "LDAP/AD"). */
@RestController
public class DirectoryController {

    public record ConnectionBody(boolean enabled, String url, String bindDn, String bindPassword, String userBase,
            String userFilter, String groupBase, String memberAttribute, String displayNameAttribute,
            String mailAttribute, String disabledGroupDn) {
    }

    private final DirectoryService directory;

    public DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/api/v1/directory")
    public ConnectionView get() {
        return directory.get(me());
    }

    @PutMapping("/api/v1/directory")
    public ConnectionView save(@RequestBody ConnectionBody b) {
        return directory.save(me(), new Connection(b.enabled(), b.url(), b.bindDn(), null, b.userBase(), b.userFilter(),
                b.groupBase(), b.memberAttribute(), b.displayNameAttribute(), b.mailAttribute(), b.disabledGroupDn(),
                null, null, null), b.bindPassword());
    }

    @PostMapping("/api/v1/directory/test")
    public Map<String, Object> test() {
        return directory.test(me());
    }

    @GetMapping("/api/v1/directory/mappings")
    public List<Mapping> mappings() {
        return directory.mappings(me());
    }

    @PutMapping("/api/v1/directory/mappings")
    public List<Mapping> saveMappings(@RequestBody List<Mapping> body) {
        return directory.saveMappings(me(), body);
    }

    @PostMapping("/api/v1/directory/sync")
    public SyncResult sync() {
        return directory.syncNow(me());
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}

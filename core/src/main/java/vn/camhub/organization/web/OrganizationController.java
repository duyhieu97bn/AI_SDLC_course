package vn.camhub.organization.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.organization.api.CameraLocationPort.Location;
import vn.camhub.organization.application.GroupService;
import vn.camhub.organization.application.MoveCameraService;
import vn.camhub.organization.application.SiteService;
import vn.camhub.organization.application.SiteService.SiteNode;
import vn.camhub.organization.infrastructure.OrganizationRepository;
import vn.camhub.organization.infrastructure.OrganizationRepository.GroupRow;
import vn.camhub.organization.infrastructure.OrganizationRepository.HistoryRow;
import vn.camhub.platform.security.CurrentUser;

/** /sites, /sites/{id}/zones, /camera-groups, POST /cameras/{id}/move (SADD 3.7.1, UC-14). */
@RestController
@RequestMapping("/api/v1")
public class OrganizationController {

    public record SiteRequest(@NotBlank @Size(max = 200) String name, @Size(max = 500) String address, String timezone) {
    }

    public record SitePatch(@Size(max = 200) String name, @Size(max = 500) String address, String timezone) {
    }

    public record NameRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record GroupRequest(@NotBlank @Size(max = 200) String name, @Size(max = 500) String description) {
    }

    public record MembersRequest(List<UUID> add, List<UUID> remove) {
    }

    public record MoveRequest(@NotNull UUID zoneId) {
    }

    private final SiteService sites;
    private final GroupService groups;
    private final MoveCameraService move;
    private final OrganizationRepository repo;

    public OrganizationController(SiteService sites, GroupService groups, MoveCameraService move,
            OrganizationRepository repo) {
        this.sites = sites;
        this.groups = groups;
        this.move = move;
        this.repo = repo;
    }

    @GetMapping("/sites")
    public List<SiteNode> tree() {
        return sites.tree(me());
    }

    @PostMapping("/sites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createSite(@Valid @RequestBody SiteRequest r) {
        return Map.of("id", sites.createSite(me(), r.name(), r.address(), r.timezone()));
    }

    @PatchMapping("/sites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSite(@PathVariable UUID id, @Valid @RequestBody SitePatch r) {
        sites.updateSite(me(), id, r.name(), r.address(), r.timezone());
    }

    @DeleteMapping("/sites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSite(@PathVariable UUID id) {
        sites.deleteSite(me(), id);
    }

    @PostMapping("/sites/{siteId}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createZone(@PathVariable UUID siteId, @Valid @RequestBody NameRequest r) {
        return Map.of("id", sites.createZone(me(), siteId, r.name()));
    }

    @PatchMapping("/zones/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void renameZone(@PathVariable UUID id, @Valid @RequestBody NameRequest r) {
        sites.renameZone(me(), id, r.name());
    }

    @DeleteMapping("/zones/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteZone(@PathVariable UUID id) {
        sites.deleteZone(me(), id);
    }

    @GetMapping("/camera-groups")
    public List<GroupRow> groups() {
        return groups.list();
    }

    @PostMapping("/camera-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createGroup(@Valid @RequestBody GroupRequest r) {
        return Map.of("id", groups.create(me(), r.name(), r.description()));
    }

    @PatchMapping("/camera-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateGroup(@PathVariable UUID id, @RequestBody GroupRequest r) {
        groups.update(me(), id, r.name(), r.description());
    }

    @DeleteMapping("/camera-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable UUID id) {
        groups.delete(me(), id);
    }

    @GetMapping("/camera-groups/{id}/cameras")
    public List<UUID> members(@PathVariable UUID id) {
        return groups.members(id);
    }

    @PutMapping("/camera-groups/{id}/cameras")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMembers(@PathVariable UUID id, @RequestBody MembersRequest r) {
        groups.setMembers(me(), id, r.add() == null ? List.of() : r.add(), r.remove() == null ? List.of() : r.remove());
    }

    @PostMapping("/cameras/{id}/move")
    public Location move(@PathVariable UUID id, @Valid @RequestBody MoveRequest r) {
        return move.move(me(), id, r.zoneId());
    }

    @GetMapping("/cameras/{id}/location-history")
    @PreAuthorize("hasPermission(#id, 'CAMERA', 'VIEW_LIVE') or hasPermission(#id, 'CAMERA', 'CONFIGURE_CAMERA')")
    public List<HistoryRow> history(@PathVariable UUID id) {
        return repo.history(id);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}

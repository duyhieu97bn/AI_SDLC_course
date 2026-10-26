package vn.camhub.liveview.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.liveview.application.LayoutService;
import vn.camhub.liveview.application.LiveSessionService;
import vn.camhub.liveview.application.LiveSessionService.LiveSession;
import vn.camhub.liveview.application.LiveSessionService.Stream;
import vn.camhub.liveview.application.PtzService;
import vn.camhub.liveview.application.PtzService.PresetView;
import vn.camhub.liveview.application.PtzService.PtzCommand;
import vn.camhub.liveview.application.TalkService;
import vn.camhub.liveview.application.TalkService.TalkEnd;
import vn.camhub.liveview.application.TalkService.TalkStart;
import vn.camhub.liveview.domain.ViewLayout;
import vn.camhub.liveview.domain.ViewLayout.Tile;
import vn.camhub.platform.security.CurrentUser;

/** Xem trực tiếp (US-08), PTZ và preset (US-12, US-13), đàm thoại (US-15), bố cục xem (US-10) – SADD 3.7.1 nhóm "Xem trực tiếp". */
@RestController
@RequestMapping("/api/v1")
public class LiveController {

    public record SessionRequest(Stream stream) {
    }

    public record PtzRequest(@Min(-1) @Max(1) double pan, @Min(-1) @Max(1) double tilt, @Min(-1) @Max(1) double zoom) {
    }

    public record PresetRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record LayoutRequest(@NotBlank @Size(max = 100) String name, int gridSize, List<Tile> tiles) {
    }

    private final LiveSessionService sessions;
    private final PtzService ptz;
    private final TalkService talk;
    private final LayoutService layouts;

    public LiveController(LiveSessionService sessions, PtzService ptz, TalkService talk, LayoutService layouts) {
        this.sessions = sessions;
        this.ptz = ptz;
        this.talk = talk;
        this.layouts = layouts;
    }

    /** Cấp URL WHEP/HLS + token 60 giây. Trình duyệt gọi lại định kỳ: bị thu hồi quyền thì nhận 403 (US-32 AC2). */
    @PostMapping("/cameras/{id}/live-session")
    public LiveSession liveSession(@PathVariable UUID id, @RequestBody(required = false) SessionRequest body) {
        return sessions.open(me(), id, body == null || body.stream() == null ? Stream.MAIN : body.stream());
    }

    @PostMapping("/cameras/{id}/ptz")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ptz(@PathVariable UUID id, @Valid @RequestBody PtzRequest body) {
        ptz.move(me(), id, new PtzCommand(body.pan(), body.tilt(), body.zoom()));
    }

    @PostMapping("/cameras/{id}/ptz/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ptzStop(@PathVariable UUID id) {
        ptz.stop(me(), id);
    }

    /** Nhấn giữ để nói (US-15): mở kênh âm thanh tới loa camera, trả URL WebSocket gửi âm thanh. */
    @PostMapping("/cameras/{id}/talk")
    @ResponseStatus(HttpStatus.CREATED)
    public TalkStart talkStart(@PathVariable UUID id) {
        return talk.start(me(), id);
    }

    /** Nhả nút: kết thúc phiên và ghi TALK_AUDIO kèm thời lượng. */
    @PostMapping("/cameras/{id}/talk/{sessionId}/end")
    public TalkEnd talkEnd(@PathVariable UUID id, @PathVariable String sessionId) {
        return talk.end(me(), id, sessionId);
    }

    @GetMapping("/cameras/{id}/presets")
    public List<PresetView> presets(@PathVariable UUID id) {
        return ptz.presets(me(), id);
    }

    @PostMapping("/cameras/{id}/presets")
    @ResponseStatus(HttpStatus.CREATED)
    public PresetView savePreset(@PathVariable UUID id, @Valid @RequestBody PresetRequest body) {
        return ptz.savePreset(me(), id, body.name());
    }

    @PostMapping("/cameras/{id}/presets/{token}/goto")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void gotoPreset(@PathVariable UUID id, @PathVariable String token) {
        ptz.gotoPreset(me(), id, token);
    }

    @DeleteMapping("/cameras/{id}/presets/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePreset(@PathVariable UUID id, @PathVariable String token) {
        ptz.removePreset(me(), id, token);
    }

    @GetMapping("/layouts")
    public List<ViewLayout> layouts() {
        return layouts.list(me());
    }

    @PostMapping("/layouts")
    @ResponseStatus(HttpStatus.CREATED)
    public ViewLayout create(@Valid @RequestBody LayoutRequest body) {
        return layouts.create(me(), body.name(), body.gridSize(), body.tiles() == null ? List.of() : body.tiles());
    }

    @PutMapping("/layouts/{id}")
    public ViewLayout update(@PathVariable UUID id, @Valid @RequestBody LayoutRequest body) {
        return layouts.update(me(), id, body.name(), body.gridSize(), body.tiles() == null ? List.of() : body.tiles());
    }

    @PostMapping("/layouts/{id}/use")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void use(@PathVariable UUID id) {
        layouts.use(me(), id);
    }

    @DeleteMapping("/layouts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        layouts.delete(me(), id);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}

package camhub.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Tải API của 100 Operator đồng thời trong 1 giờ (TS-08.1, NFR-03). Mỗi người dùng mô phỏng một màn hình giám sát:
 *
 * <ul>
 *   <li>Đăng nhập, tải danh sách camera (Operator vào thẳng Xem trực tiếp; Tổng quan cần MANAGE_SYSTEM).</li>
 *   <li>Lưới 2×2: cứ khoảng 50 giây xin lại phiên xem cho 4 camera ngẫu nhiên (luồng phụ) – đúng nhịp trình duyệt
 *       làm mới token 60 giây của MediaMTX.</li>
 *   <li>Mỗi vòng đọc số cảnh báo mở và số thông báo chưa đọc (chuông thông báo); 20% vòng mở xem lại 1 giờ gần nhất,
 *       10% mở danh sách cảnh báo, 5% tải lại cây camera.</li>
 *   <li>Token truy cập hết hạn sau 15 phút: đăng nhập lại sau 12 phút (cookie làm mới có cờ Secure nên không dùng
 *       được khi gọi thẳng Core qua HTTP trong mạng nội bộ).</li>
 * </ul>
 *
 * <p>Mặc định gọi thẳng Core ({@code http://core:8080}): Nginx giới hạn 30 yêu cầu/giây mỗi IP, mà toàn bộ người
 * dùng ảo đi ra từ một IP. Tham số: {@code -Dusers=100 -Dminutes=60 -DrampSeconds=120 -DbaseUrl=...}.
 */
public class ApiLoadSimulation extends Simulation {

    static final String BASE = System.getProperty("baseUrl", "http://core:8080");
    static final int USERS = Integer.getInteger("users", 100);
    static final int MINUTES = Integer.getInteger("minutes", 60);
    static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 120);
    static final long RELOGIN_MS = Duration.ofMinutes(12).toMillis();

    static final String LIVE = "Phiên xem trực tiếp";

    @Override
    public void before() {
        LoadUsers.ensure(BASE, System.getenv().getOrDefault("CAMHUB_ADMIN_EMAIL", "admin@camhub.local"),
                System.getenv("CAMHUB_ADMIN_PASSWORD"), USERS);
    }

    static HttpRequestActionBuilder authed(HttpRequestActionBuilder r) {
        return r.header("Authorization", "Bearer #{token}");
    }

    final Iterator<Map<String, Object>> accounts = IntStream.rangeClosed(1, USERS)
            .mapToObj(i -> Map.<String, Object>of("email", LoadUsers.email(i))).iterator();

    final HttpProtocolBuilder protocol = http.baseUrl(BASE)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("CamHub-Gatling");

    final ChainBuilder login = exec(http("Đăng nhập").post("/api/v1/auth/login")
            .body(StringBody("{\"email\":\"#{email}\",\"password\":\"" + LoadUsers.PASSWORD + "\"}"))
            .check(status().is(200), jsonPath("$.accessToken").saveAs("token")))
            .exec(s -> s.set("tokenAt", System.currentTimeMillis()));

    final ChainBuilder pickGrid = exec(s -> {
        List<String> ids = new ArrayList<>(s.getList("cameraIds"));
        Collections.shuffle(ids);
        return s.set("grid", ids.subList(0, Math.min(4, ids.size())));
    });

    final ChainBuilder playback = exec(s -> {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<String> grid = s.getList("grid");
        return s.set("pbCam", grid.isEmpty() ? "" : grid.get(0)).set("pbFrom", now.minus(Duration.ofHours(1)).toString())
                .set("pbTo", now.toString());
    })
            .exec(authed(http("Dòng thời gian xem lại").get("/api/v1/cameras/#{pbCam}/timeline")
                    .queryParam("from", "#{pbFrom}").queryParam("to", "#{pbTo}")
                    .check(status().is(200), jsonPath("$.recordings[0].start").optional().saveAs("pbAt"))))
            .doIf(s -> s.contains("pbAt")).then(
                    exec(authed(http("Phiên xem lại").post("/api/v1/cameras/#{pbCam}/playback-session")
                            .body(StringBody("{\"at\":\"#{pbAt}\"}")).check(status().is(200))))
                            .exec(s -> s.remove("pbAt")));

    final ScenarioBuilder operator = scenario("Operator giám sát")
            .exec(feed(accounts))
            .exec(login)
            .exec(authed(http("Danh sách camera").get("/api/v1/cameras").queryParam("size", "1000")
                    .check(status().is(200), jsonPath("$.items[*].id").findAll().saveAs("cameraIds"))))
            .exec(during(Duration.ofMinutes(MINUTES)).on(
                    doIf(s -> System.currentTimeMillis() - s.getLong("tokenAt") > RELOGIN_MS).then(login)
                            .exec(pickGrid)
                            .foreach("#{grid}", "cam").on(exec(authed(http(LIVE)
                                    .post("/api/v1/cameras/#{cam}/live-session")
                                    .body(StringBody("{\"stream\":\"SUB\"}"))
                                    .check(status().is(200), jsonPath("$.whepUrl").exists()))))
                            .exec(authed(http("Số cảnh báo mở").get("/api/v1/alerts/open-count")
                                    .check(status().is(200))))
                            .exec(authed(http("Thông báo chưa đọc").get("/api/v1/me/notifications/unread-count")
                                    .check(status().is(200))))
                            .exec(randomSwitch().on(
                                    percent(20.0).then(playback),
                                    percent(10.0).then(exec(authed(http("Danh sách cảnh báo")
                                            .get("/api/v1/alerts").queryParam("size", "20").check(status().is(200))))),
                                    percent(5.0).then(exec(authed(http("Danh sách camera")
                                            .get("/api/v1/cameras").queryParam("size", "1000")
                                            .check(status().is(200)))))))
                            .pause(Duration.ofSeconds(45), Duration.ofSeconds(55))));

    {
        setUp(operator.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(RAMP_SECONDS))))
                .protocols(protocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().responseTime().percentile(95.0).lt(500),
                        details(LIVE).responseTime().percentile(95.0).lt(300));
    }
}

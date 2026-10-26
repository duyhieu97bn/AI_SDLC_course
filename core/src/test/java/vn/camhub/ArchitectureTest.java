package vn.camhub;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ranh giới modular monolith (SADD 3.4, 3.8). Chạy trong CI ở mọi pull request.
 */
class ArchitectureTest {

    static final String ROOT = "vn.camhub";
    static final List<String> MODULES = List.of("identity", "access", "organization", "device", "liveview",
            "recording", "export", "configuration", "alerting", "notification", "audit", "system");

    static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /** Không có phụ thuộc vòng giữa các module (kể cả platform). */
    static final ArchRule NO_MODULE_CYCLES = slices().matching(ROOT + ".(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    /** Module chỉ gọi module khác qua package {@code api}. */
    static final ArchRule ONLY_VIA_API = classes().that().resideInAnyPackage(
                    MODULES.stream().map(m -> ROOT + "." + m + "..").toArray(String[]::new))
            .should(onlyDependOnOtherModulesThroughApi())
            .allowEmptyShould(true);

    static final ArchRule PLATFORM_INDEPENDENT = noClasses().that().resideInAPackage(ROOT + ".platform..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    MODULES.stream().map(m -> ROOT + "." + m + "..").toArray(String[]::new))
            .allowEmptyShould(true);

    /** Trong module: web → application → domain; domain không biết web, application hay infrastructure. */
    static final ArchRule DOMAIN_IS_INNERMOST = noClasses().that().resideInAPackage(ROOT + ".*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".*.web..", ROOT + ".*.application..",
                    ROOT + ".*.infrastructure..")
            .allowEmptyShould(true);

    static final ArchRule APPLICATION_NOT_WEB = noClasses().that().resideInAPackage(ROOT + ".*.application..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".*.web..")
            .allowEmptyShould(true);

    @Test
    void modulesHaveNoCycles() {
        NO_MODULE_CYCLES.check(production);
    }

    @Test
    void modulesTalkThroughApiOnly() {
        ONLY_VIA_API.check(production);
    }

    @Test
    void platformDoesNotDependOnBusinessModules() {
        PLATFORM_INDEPENDENT.check(production);
    }

    @Test
    void layersPointInward() {
        DOMAIN_IS_INNERMOST.check(production);
        APPLICATION_NOT_WEB.check(production);
    }

    /** Tiêu chí TS-01 "Phát hiện phụ thuộc vòng giữa module": rule phải bắt được vòng a → b → a. */
    @Test
    void cycleRuleDetectsCycles() {
        JavaClasses fixture = new ClassFileImporter().importPackages("vn.camhub.archfixture");
        ArchRule rule = slices().matching("vn.camhub.archfixture.(*)..").should().beFreeOfCycles();
        assertThrows(AssertionError.class, () -> rule.check(fixture));
    }

    private static ArchCondition<JavaClass> onlyDependOnOtherModulesThroughApi() {
        return new ArchCondition<>("chỉ phụ thuộc module khác qua package api") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String own = moduleOf(item.getPackageName()).orElse("");
                for (Dependency d : item.getDirectDependenciesFromSelf()) {
                    String targetPkg = d.getTargetClass().getPackageName();
                    Optional<String> target = moduleOf(targetPkg);
                    if (target.isPresent() && !target.get().equals(own)
                            && !targetPkg.startsWith(ROOT + "." + target.get() + ".api")) {
                        events.add(SimpleConditionEvent.violated(item, d.getDescription()));
                    }
                }
            }
        };
    }

    static Optional<String> moduleOf(String pkg) {
        return MODULES.stream().filter(m -> pkg.equals(ROOT + "." + m) || pkg.startsWith(ROOT + "." + m + "."))
                .findFirst();
    }
}

package br.com.pixmanager.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda-corpos da arquitetura hexagonal (docs/03 §2.1, regras A1–A8), no mesmo formato do mesa-credito-full:
 * {@code api → core ← infrastructure}; o core é Java puro; erro de dependência quebra o build AQUI, não na revisão.
 */
@DisplayName("Arquitetura hexagonal (ArchUnit)")
class HexagonalArchitectureTest {

    private static final String BASE = "br.com.pixmanager";
    private static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(BASE);
    }

    @Test
    @DisplayName("A1: o core não depende de Spring, JPA, Hibernate, Kafka, Avro, Jackson, Lombok nem Bean Validation")
    void coreIsPureJava() {
        ArchRule rule = noClasses().that().resideInAPackage("..core..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "org.apache.kafka..", "io.confluent..", "org.apache.avro..", "com.fasterxml.jackson..", "lombok..",
                        "jakarta.validation..", "io.github.resilience4j..", "br.com.pixmanager.events..");
        rule.check(classes);
    }

    @Test
    @DisplayName("A2: o core não depende de infrastructure nem de api")
    void coreDoesNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..core..").should().dependOnClassesThat()
                .resideInAnyPackage(BASE + ".infrastructure..", BASE + ".api..").check(classes);
    }

    @Test
    @DisplayName("A3: api não depende de infrastructure (controller/consumer chamam um UseCase)")
    void apiDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat().resideInAPackage(BASE + ".infrastructure..").check(classes);
    }

    @Test
    @DisplayName("A4: infrastructure não depende de api")
    void infrastructureDoesNotDependOnApi() {
        noClasses().that().resideInAPackage("..infrastructure..").should().dependOnClassesThat().resideInAPackage(BASE + ".api..").check(classes);
    }

    @Test
    @DisplayName("A5: shared (fora de config, mappers e handler) não depende de core, infrastructure nem api")
    void sharedIsIndependent() {
        noClasses().that().resideInAPackage("..shared..")
                .and().resideOutsideOfPackages("..shared.config..", "..shared.mappers..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + ".core..", BASE + ".infrastructure..", BASE + ".api..").check(classes);
    }

    @Test
    @DisplayName("A6: domínio não depende de application nem de ports (a dependência vai para dentro)")
    void domainIsTheCenter() {
        noClasses().that().resideInAPackage("..core.domain..").should().dependOnClassesThat()
                .resideInAnyPackage(BASE + ".core.application..", BASE + ".core.port..").check(classes);
    }

    @Test
    @DisplayName("A7: services do core são classes puras: sem @Service/@Component/@Transactional (viram bean no UseCaseConfig)")
    void servicesAreWiredInUseCaseConfigNotAnnotated() {
        noClasses().that().resideInAPackage("..core.application.service..")
                .should().beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Component")
                .orShould().beAnnotatedWith("org.springframework.transaction.annotation.Transactional").check(classes);
    }

    @Test
    @DisplayName("Convenção de nomes: *UseCase em port.in, *PortOut em port.out, *Adapter em infrastructure, *Entity em persistence.entity")
    void namingFollowsLayerPlacement() {
        classes().that().haveSimpleNameEndingWith("UseCase").and().areInterfaces().should().resideInAPackage("..core.port.in..").check(classes);
        classes().that().haveSimpleNameEndingWith("PortOut").should().resideInAPackage("..core.port.out..").check(classes);
        classes().that().haveSimpleNameEndingWith("Adapter").should().resideInAPackage("..infrastructure..").check(classes);
        classes().that().haveSimpleNameEndingWith("Entity").and().areNotAssignableTo(Throwable.class)
                .and().resideOutsideOfPackages("br.com.pixmanager.events..")
                .should().resideInAPackage("..infrastructure.persistence.entity..").check(classes);
        classes().that().haveSimpleNameEndingWith("Service").and().resideInAPackage("..core..")
                .should().resideInAPackage("..core.application.service..").check(classes);
    }

    @Test
    @DisplayName("Service sem sufixo Impl (convenção architecture-conventions)")
    void noImplSuffixOnServices() {
        noClasses().that().resideInAPackage("..core.application.service..").should().haveSimpleNameEndingWith("Impl").check(classes);
    }

    @Test
    @DisplayName("A8: todo método de controller REST tem @PreAuthorize (na interface do contrato) — nenhum endpoint fica aberto por esquecimento")
    void everyControllerMethodIsGuarded() {
        List<JavaMethod> unguarded = classes.stream()
                .filter(javaClass -> javaClass.isInterface() && javaClass.isAnnotatedWith(RequestMapping.class) && javaClass.getPackageName().contains(".api.rest."))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> !method.isAnnotatedWith(PreAuthorize.class))
                .toList();
        assertThat(unguarded).as("métodos de controller sem @PreAuthorize").isEmpty();
    }

    @Test
    @DisplayName("Todo literal de escopo em @PreAuthorize usa as constantes de ScopeAuthorization (typo vira erro de build)")
    void scopesComeFromTheCatalog() throws Exception {
        List<String> catalog = java.util.Arrays.stream(br.com.pixmanager.api.rest.security.ScopeAuthorization.class.getFields())
                .filter(field -> field.getType() == String.class).map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException exception) {
                        throw new IllegalStateException(exception);
                    }
                }).toList();
        List<String> offenders = classes.stream()
                .filter(javaClass -> javaClass.isInterface() && javaClass.getPackageName().contains(".api.rest."))
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(PreAuthorize.class))
                .map(method -> method.getAnnotationOfType(PreAuthorize.class).value())
                .filter(permission -> !catalog.contains(permission)).toList();
        assertThat(offenders).as("@PreAuthorize com literal fora do catálogo").isEmpty();
    }
}

package com.flashopify.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Modules may only reach each other through service interfaces or events, never via mappers. */
class ModuleBoundaryTest {

  private static final String BASE = "com.flashopify";
  private static final List<String> MODULES =
      List.of(
          "auth",
          "product",
          "inventory",
          "flashsale",
          "order",
          "payment",
          "notification",
          "ai",
          "admin");

  private static JavaClasses production;

  @BeforeAll
  static void importProductionClasses() {
    production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);
  }

  /** Guards against a vacuous rule: mapper classes must actually be part of the import. */
  @Test
  void importedClassesIncludeModuleMappers() {
    for (String module : List.of("auth", "product", "inventory", "flashsale")) {
      assertThat(production.stream().filter(c -> c.getPackageName().equals(mapperPackage(module))))
          .as("mapper classes of module %s are imported", module)
          .isNotEmpty();
    }
  }

  @Test
  void classesOutsideAModuleDoNotDependOnItsMapperPackage() {
    for (String module : MODULES) {
      noClasses()
          .that()
          .resideOutsideOfPackage(BASE + "." + module + "..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(mapperPackage(module) + "..")
          .because("module '" + module + "' must be accessed through its service interface")
          .check(production);
    }
  }

  private static String mapperPackage(String module) {
    return BASE + "." + module + ".mapper";
  }
}

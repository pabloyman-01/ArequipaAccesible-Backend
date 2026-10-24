package pe.arequipa.accesible;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void coreDoesNotDependOnFrameworksOrAdapters() {
        noClasses().that().resideInAnyPackage("..domain..","..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..","jakarta..",
                        "..infrastructure..","..presentation..")
                .check(new ClassFileImporter().importPackages("pe.arequipa.accesible"));
    }
}

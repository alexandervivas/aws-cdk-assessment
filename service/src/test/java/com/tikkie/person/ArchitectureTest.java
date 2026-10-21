package com.tikkie.person;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.tikkie.person", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnlyOnTheJdkAndPhoneParsing = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.tikkie.person.domain..",
                    "java..",
                    // Phone validity is a domain rule; libphonenumber is a pure library with no I/O or framework.
                    "com.google.i18n.phonenumbers..");

    @ArchTest
    static final ArchRule applicationIsFreeOfAwsJacksonAndAdapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "software.amazon..", "com.amazonaws..", "com.fasterxml..", "..adapter..");
}

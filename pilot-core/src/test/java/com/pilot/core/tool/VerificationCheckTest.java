package com.pilot.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerificationCheckTest {
    @Test
    void requiresBothAQualifyingCommandAndSuccessfulFinalExitCode() {
        assertEquals(VerificationCheck.Kind.TEST,
                VerificationCheck.passingKind("mvn -Dlabel=exit_1 test → exit 0"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.passingKind("mvn test → exit 1"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.passingKind("git status → exit 0"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.passingKind("node --version → exit 0"));
    }

    @Test
    void exposesTypedKindsForSupportedVerificationFamilies() {
        assertEquals(VerificationCheck.Kind.TEST, VerificationCheck.classifyCommand("npm test"));
        assertEquals(VerificationCheck.Kind.BUILD, VerificationCheck.classifyCommand("javac Main.java"));
        assertEquals(VerificationCheck.Kind.STATIC_ANALYSIS, VerificationCheck.classifyCommand("npx eslint ."));
        assertEquals(VerificationCheck.Kind.FORMAT_CHECK,
                VerificationCheck.classifyCommand("cargo fmt --check"));
        assertEquals(VerificationCheck.Kind.TEST, VerificationCheck.classifyCommand("ctest --output-on-failure"));
        assertEquals(VerificationCheck.Kind.STATIC_ANALYSIS,
                VerificationCheck.classifyCommand("make typecheck"));
        assertEquals(VerificationCheck.Kind.TEST,
                VerificationCheck.classifyCommand("npm run behaviour"));
    }

    @Test
    void rejectsVersionHelpAndEnvironmentProbes() {
        for (String command : java.util.List.of(
                "javac -version",
                "rustc --version",
                "tsc --version",
                "ctest --version",
                "pytest --version",
                "python -m pytest --help",
                "npx eslint --version",
                "npx jest --help",
                "mvn --version test",
                "gradlew --version test",
                "cargo metadata",
                "java -version")) {
            assertEquals(VerificationCheck.Kind.OTHER, VerificationCheck.classifyCommand(command), command);
        }
    }

    @Test
    void acceptsOnlyKnownDirectShellWrappers() {
        assertEquals(VerificationCheck.Kind.TEST,
                VerificationCheck.classifyCommand("bash ./mvnw test"));
        assertEquals(VerificationCheck.Kind.BUILD,
                VerificationCheck.classifyCommand("sh ./gradlew build"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("bash -lc ./mvnw test"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("bash ./verify.sh"));
    }

    @Test
    void doesNotInferTestsFromArbitraryTaskOrRunnerNames() {
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("gradlew latest"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("gradlew testClasses"));
        assertEquals(VerificationCheck.Kind.TEST,
                VerificationCheck.classifyCommand("gradlew testDebugUnitTest"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("npm run contest"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("npx playwright install"));
        assertEquals(VerificationCheck.Kind.OTHER,
                VerificationCheck.classifyCommand("npx cypress open"));
        assertEquals(VerificationCheck.Kind.TEST,
                VerificationCheck.classifyCommand("npx playwright test"));
        assertEquals(VerificationCheck.Kind.TEST,
                VerificationCheck.classifyCommand("npx cypress run"));
    }
}

package com.cragent.tools;

import java.util.List;
import java.util.Map;

import static com.cragent.tools.ToolSchemas.*;

public class TestGenerationTools {
    public void register(ToolRouter router) {
        router.register(new ToolSpec("infer_test_path", "Infer test path for a source file.", object(Map.of(
                "source_path", str("Source path"),
                "framework", str("Test framework")
        ), List.of("source_path")), this::inferTestPath, false));
        router.register(new ToolSpec("generate_tests_for_changes", "Generate unit test stubs for changed files.", object(Map.of(
                "changed_files", array("Changed files"),
                "framework", str("Test framework")
        ), List.of()), this::generateTestsForChanges, false));
    }

    public Object inferTestPath(Map<String, Object> args) {
        String source = String.valueOf(args.get("source_path"));
        String framework = String.valueOf(args.getOrDefault("framework", "pytest"));
        String testPath;
        if (framework.equals("pytest") || framework.equals("unittest")) {
            String filename = source.substring(source.lastIndexOf('/') + 1);
            String stem = filename.endsWith(".py") ? filename.substring(0, filename.length() - 3) : filename;
            String parent = source.contains("/") ? source.substring(0, source.lastIndexOf('/')) : "";
            parent = parent.replaceFirst("^src/?", "").replaceFirst("^app/?", "");
            testPath = "tests/" + (parent.isBlank() ? "" : parent + "/") + "test_" + stem + ".py";
        } else if (framework.equals("junit5") || framework.equals("junit4") || framework.equals("testng") || framework.equals("spring-boot-test") || framework.equals("kotest")) {
            if (source.startsWith("src/main/java/") || source.startsWith("src/main/kotlin/")) {
                testPath = source.replaceFirst("^src/main/java/", "src/test/java/")
                        .replaceFirst("^src/main/kotlin/", "src/test/kotlin/")
                        .replaceFirst("\\.java$", "Test.java")
                        .replaceFirst("\\.kt$", "Test.kt");
            } else {
                int dot = source.lastIndexOf('.');
                String ext = source.endsWith(".kt") ? ".kt" : ".java";
                testPath = dot > 0 ? source.substring(0, dot) + "Test" + ext : source + "Test" + ext;
            }
        } else if (framework.equals("cargo-test") || framework.equals("tokio-test") || framework.equals("rstest")) {
            if (source.startsWith("src/")) {
                int dot = source.lastIndexOf('.');
                testPath = dot > 0 ? source.substring(0, dot) + "_test.rs" : source + "_test.rs";
            } else {
                String filename = source.substring(source.lastIndexOf('/') + 1).replaceFirst("\\.rs$", "");
                testPath = "tests/" + filename + "_test.rs";
            }
        } else if (framework.equals("testing")) {
            int dot = source.lastIndexOf('.');
            testPath = dot > 0 ? source.substring(0, dot) + "_test.go" : source + "_test.go";
        } else if (framework.equals("react-testing-library") || framework.equals("vue-test-utils") || framework.equals("angular-testing")) {
            int dot = source.lastIndexOf('.');
            String suffix = source.endsWith(".ts") || source.endsWith(".tsx") ? ".test.tsx" : ".test.jsx";
            testPath = dot > 0 ? source.substring(0, dot) + suffix : source + suffix;
        } else if (framework.equals("playwright") || framework.equals("cypress")) {
            String filename = source.substring(source.lastIndexOf('/') + 1).replaceAll("\\.[^.]+$", "");
            testPath = "e2e/" + filename + "." + (framework.equals("playwright") ? "spec.ts" : "cy.ts");
        } else if (framework.equals("phpunit") || framework.equals("pest") || framework.equals("laravel-test")) {
            String filename = source.substring(source.lastIndexOf('/') + 1).replaceFirst("\\.php$", "");
            String parent = source.contains("/") ? source.substring(0, source.lastIndexOf('/')) : "";
            parent = parent.replaceFirst("^app/?", "");
            testPath = "tests/" + (parent.isBlank() ? "" : parent + "/") + filename + "Test.php";
        } else if (framework.equals("rspec") || framework.equals("rails-test")) {
            testPath = "spec/" + source.replaceFirst("^app/?", "").replaceFirst("\\.rb$", "_spec.rb");
        } else if (framework.equals("minitest")) {
            testPath = "test/" + source.replaceFirst("^app/?", "").replaceFirst("\\.rb$", "_test.rb");
        } else if (framework.equals("xunit") || framework.equals("nunit") || framework.equals("mstest") || framework.equals("moq")) {
            String filename = source.substring(source.lastIndexOf('/') + 1).replaceFirst("\\.cs$", "");
            testPath = "tests/" + filename + "Tests.cs";
        } else if (framework.equals("swift-testing") || framework.equals("xctest") || framework.equals("quick-nimble")) {
            String filename = source.substring(source.lastIndexOf('/') + 1).replaceFirst("\\.swift$", "");
            testPath = "Tests/" + filename + "Tests.swift";
        } else {
            int dot = source.lastIndexOf('.');
            testPath = dot > 0 ? source.substring(0, dot) + ".spec" + source.substring(dot) : source + ".spec";
        }
        return Map.of("source_path", source, "test_path", testPath, "framework", framework);
    }

    @SuppressWarnings("unchecked")
    public Object generateTestsForChanges(Map<String, Object> args) {
        List<Map<String, Object>> files = (List<Map<String, Object>>) args.getOrDefault("changed_files", List.of());
        String framework = String.valueOf(args.getOrDefault("framework", "pytest"));
        List<Map<String, Object>> generated = files.stream()
                .filter(f -> shouldGenerate(String.valueOf(f.getOrDefault("filename", f.getOrDefault("path", ""))), String.valueOf(f.getOrDefault("patch", "")), ((Number) f.getOrDefault("additions", 0)).intValue()))
                .map(f -> {
                    String filename = String.valueOf(f.getOrDefault("filename", f.getOrDefault("path", "")));
                    String testPath = (String) ((Map<String, Object>) inferTestPath(Map.of("source_path", filename, "framework", framework))).get("test_path");
                    return Map.<String, Object>of("path", testPath, "content", renderStub(filename, framework));
                })
                .toList();
        return Map.of("generated_tests", generated, "count", generated.size());
    }

    private static boolean shouldGenerate(String filename, String patch, int additions) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".yaml")
                || lower.endsWith(".toml") || lower.endsWith(".xml") || lower.contains("/test") || lower.startsWith("tests/")
                || lower.endsWith("test.java") || lower.endsWith("test.kt") || lower.endsWith("_test.go") || lower.endsWith("_test.rs")
                || lower.endsWith("test.php") || lower.endsWith("_spec.rb") || lower.endsWith("_test.rb") || lower.endsWith("tests.cs") || lower.endsWith("tests.swift")
                || lower.endsWith(".spec.ts") || lower.endsWith(".spec.tsx") || lower.endsWith(".test.js") || lower.endsWith(".test.ts") || lower.endsWith(".test.tsx")) {
            return false;
        }
        return additions > 20 || patch.contains("def ") || patch.contains("function ") || patch.contains("class ") || patch.contains("=>")
                || patch.contains("public ") || patch.contains("private ") || patch.contains("protected ") || patch.contains("fn ")
                || patch.contains("fun ") || patch.contains("func ") || patch.contains("class ") || patch.contains("module ");
    }

    private static String renderStub(String source, String framework) {
        if (framework.equals("junit5") || framework.equals("junit4") || framework.equals("spring-boot-test")) {
            return """
                    import org.junit.jupiter.api.Test;

                    import static org.junit.jupiter.api.Assertions.*;

                    class GeneratedCoverageTest {
                        @Test
                        void coversNewBehavior() {
                            // TODO: cover %s
                            assertTrue(true);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("testng")) {
            return """
                    import org.testng.annotations.Test;
                    import static org.testng.Assert.*;

                    public class GeneratedCoverageTest {
                        @Test
                        public void coversNewBehavior() {
                            // TODO: cover %s
                            assertTrue(true);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("kotest")) {
            return """
                    import io.kotest.core.spec.style.StringSpec
                    import io.kotest.matchers.shouldBe

                    class GeneratedCoverageTest : StringSpec({
                        "covers new behavior" {
                            // TODO: cover %s
                            true shouldBe true
                        }
                    })
                    """.formatted(source);
        }
        if (framework.equals("cargo-test") || framework.equals("tokio-test") || framework.equals("rstest")) {
            return """
                    #[cfg(test)]
                    mod tests {
                        #[test]
                        fn covers_new_behavior() {
                            // TODO: cover %s
                            assert!(true);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("jest") || framework.equals("vitest")) {
            return "describe('" + source + "', () => {\n  it('covers the new behavior', () => {\n    expect(true).toBe(true);\n  });\n});\n";
        }
        if (framework.equals("react-testing-library")) {
            return """
                    import { render, screen } from '@testing-library/react';

                    it('covers the new behavior', () => {
                      // TODO: render component from %s
                      expect(true).toBe(true);
                    });
                    """.formatted(source);
        }
        if (framework.equals("vue-test-utils")) {
            return """
                    import { mount } from '@vue/test-utils';

                    test('covers the new behavior', () => {
                      // TODO: mount component from %s
                      expect(true).toBe(true);
                    });
                    """.formatted(source);
        }
        if (framework.equals("playwright")) {
            return """
                    import { test, expect } from '@playwright/test';

                    test('covers the new behavior', async ({ page }) => {
                      // TODO: navigate to the flow touched by %s
                      expect(true).toBeTruthy();
                    });
                    """.formatted(source);
        }
        if (framework.equals("cypress")) {
            return """
                    describe('generated coverage', () => {
                      it('covers the new behavior', () => {
                        // TODO: exercise the flow touched by %s
                        expect(true).to.equal(true);
                      });
                    });
                    """.formatted(source);
        }
        if (framework.equals("testing")) {
            return "package main\n\nimport \"testing\"\n\nfunc TestGeneratedCoverage(t *testing.T) {\n\t// TODO: cover " + source + "\n}\n";
        }
        if (framework.equals("phpunit") || framework.equals("laravel-test")) {
            return """
                    <?php

                    use PHPUnit\\Framework\\TestCase;

                    final class GeneratedCoverageTest extends TestCase
                    {
                        public function test_covers_new_behavior(): void
                        {
                            // TODO: cover %s
                            $this->assertTrue(true);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("pest")) {
            return """
                    <?php

                    it('covers new behavior', function () {
                        // TODO: cover %s
                        expect(true)->toBeTrue();
                    });
                    """.formatted(source);
        }
        if (framework.equals("rspec") || framework.equals("rails-test")) {
            return """
                    RSpec.describe 'generated coverage' do
                      it 'covers new behavior' do
                        # TODO: cover %s
                        expect(true).to eq(true)
                      end
                    end
                    """.formatted(source);
        }
        if (framework.equals("minitest")) {
            return """
                    require 'test_helper'

                    class GeneratedCoverageTest < Minitest::Test
                      def test_covers_new_behavior
                        # TODO: cover %s
                        assert true
                      end
                    end
                    """.formatted(source);
        }
        if (framework.equals("xunit") || framework.equals("moq")) {
            return """
                    using Xunit;

                    public class GeneratedCoverageTests
                    {
                        [Fact]
                        public void CoversNewBehavior()
                        {
                            // TODO: cover %s
                            Assert.True(true);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("nunit")) {
            return """
                    using NUnit.Framework;

                    public class GeneratedCoverageTests
                    {
                        [Test]
                        public void CoversNewBehavior()
                        {
                            // TODO: cover %s
                            Assert.That(true, Is.True);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("mstest")) {
            return """
                    using Microsoft.VisualStudio.TestTools.UnitTesting;

                    [TestClass]
                    public class GeneratedCoverageTests
                    {
                        [TestMethod]
                        public void CoversNewBehavior()
                        {
                            // TODO: cover %s
                            Assert.IsTrue(true);
                        }
                    }
                    """.formatted(source);
        }
        if (framework.equals("swift-testing") || framework.equals("xctest") || framework.equals("quick-nimble")) {
            return """
                    import XCTest

                    final class GeneratedCoverageTests: XCTestCase {
                        func testCoversNewBehavior() {
                            // TODO: cover %s
                            XCTAssertTrue(true)
                        }
                    }
                    """.formatted(source);
        }
        return "def test_generated_coverage_placeholder():\n    # TODO: cover " + source + "\n    assert True\n";
    }
}

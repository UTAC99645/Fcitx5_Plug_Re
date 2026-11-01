/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * Host-side tests for the RE2 rule engine (no fcitx, no Android).
 * Build: see README (cmake -S src/main/cpp/test -B build-host && cmake --build build-host)
 */
#include "rule_engine.h"

#include <iostream>
#include <memory>
#include <string>
#include <vector>

namespace {

int failures = 0;

void check(bool condition, const std::string &name) {
    if (condition) {
        std::cout << "PASS " << name << "\n";
    } else {
        std::cout << "FAIL " << name << "\n";
        ++failures;
    }
}

std::shared_ptr<const regexcommit::RuleSet>
compile(const std::string &json, std::vector<std::string> *errors = nullptr) {
    regexcommit::RuleCompiler compiler;
    auto result = compiler.compileJson(json);
    if (errors) {
        *errors = result.errors;
    }
    return result.ruleset;
}

std::string runRules(const std::string &json, const std::string &input,
                  const std::string &package = "com.example.app",
                  bool noSpellCheck = false) {
    auto set = compile(json);
    if (!set) {
        return "<compile failed>";
    }
    regexcommit::Limits limits;
    return regexcommit::applyRules(*set, input, package, limits, noSpellCheck);
}

/** Escape a regex / replacement string so it can be embedded into JSON. */
std::string jsonEscape(const std::string &text) {
    std::string out;
    out.reserve(text.size() + 8);
    for (char c : text) {
        if (c == '\\' || c == '"') {
            out.push_back('\\');
        }
        out.push_back(c);
    }
    return out;
}

std::string ruleJson(const std::string &id, const std::string &pattern,
                     const std::string &replacement, bool applyOnce = true,
                     bool stopOnFirst = false, bool caseInsensitive = false,
                     const std::string &scopeMode = "blacklist",
                     const std::string &scopePackages = "[]") {
    return std::string("{\"id\":\"") + id + "\",\"enabled\":true,\"name\":\"t\"," +
           "\"pattern\":\"" + jsonEscape(pattern) + "\",\"replacement\":\"" +
           jsonEscape(replacement) + "\"," +
           "\"applyOncePerCommit\":" + (applyOnce ? "true" : "false") + "," +
           "\"stopOnFirstMatch\":" + (stopOnFirst ? "true" : "false") + "," +
           "\"caseInsensitive\":" + (caseInsensitive ? "true" : "false") + "," +
           "\"scope\":{\"mode\":\"" + scopeMode + "\",\"packages\":" +
           scopePackages + "}}";
}

std::string document(const std::vector<std::string> &rules,
                     bool skipNoSpellCheck = false) {
    std::string out = std::string("{\"version\":1,\"skipNoSpellCheck\":") +
                      (skipNoSpellCheck ? "true" : "false") + ",\"rules\":[";
    for (size_t i = 0; i < rules.size(); ++i) {
        if (i > 0) {
            out.push_back(',');
        }
        out += rules[i];
    }
    out += "]}";
    return out;
}

std::string oneRule(const std::string &pattern, const std::string &replacement,
                    bool applyOnce, bool stopOnFirst = false,
                    const std::string &scopeMode = "blacklist",
                    const std::string &scopePackages = "[]") {
    return document({ruleJson("a", pattern, replacement, applyOnce, stopOnFirst,
                              false, scopeMode, scopePackages)});
}

} // namespace

int main() {
    // 1. Plain replacement, replace-all mode.
    check(runRules(oneRule("foo", "bar", false), "foo foo") == "bar bar",
          "plain replace-all");

    // 2. Named capture groups (${pre} / ${post}).
    check(runRules(oneRule("(?P<pre>1[3-9]\\d)(\\d{4})(?P<post>\\d{4})",
                        "${pre}****${post}", true),
                "13812345678") == "138****5678",
          "named groups");

    // 3. Unicode script class \p{Han} in RE2.
    check(runRules(oneRule("\\p{Han}", "字", false), "中文abc") == "字字abc",
          "unicode \\p{Han}");

    // 4. Inline (?i) flag.
    check(runRules(oneRule("(?i)hello", "hi", false), "HeLLo hello") == "hi hi",
          "inline (?i)");

    // 5. Non-greedy quantifier.
    check(runRules(oneRule("a.*?b", "X", false), "a1b a22b") == "X X",
          "non-greedy");

    // 6. Lookahead must be rejected at compile time.
    std::vector<std::string> errors;
    auto rejected = compile(
        document({ruleJson("x", "foo(?=bar)", "x", true)}), &errors);
    check(!rejected && !errors.empty(), "lookahead rejected");

    // 7. Over-long commits are skipped untouched (4 KB limit).
    std::string big(5000, 'a');
    check(runRules(oneRule("a", "b", false), big) == big, "oversize text skipped");

    // 8. Chained rules: output of A feeds B.
    check(runRules(document({ruleJson("1", "a", "b", false),
                          ruleJson("2", "b", "c", false)}),
                "a") == "c",
          "chained rules");

    // 9. stopOnFirstMatch skips the next rule.
    check(runRules(document({ruleJson("1", "x", "y", false, true),
                          ruleJson("2", "y", "z", false)}),
                "x") == "y",
          "stopOnFirstMatch");

    // 10. Whitelist scope.
    const std::string scoped = oneRule("a", "b", false, false, "whitelist",
                                       R"(["com.allowed.app"])");
    check(runRules(scoped, "a", "com.allowed.app") == "b", "whitelist hit");
    check(runRules(scoped, "a", "com.other.app") == "a", "whitelist miss");

    // 10b. Blacklist scope.
    const std::string blacklisted = oneRule("a", "b", false, false, "blacklist",
                                            R"(["com.blocked.app"])");
    check(runRules(blacklisted, "a", "com.blocked.app") == "a", "blacklist hit");
    check(runRules(blacklisted, "a", "com.other.app") == "b", "blacklist miss");

    // 11. `$$` escape and literal text in replacement.
    check(runRules(oneRule("a", "$$1", true), "a") == "$1", "dollar escape");

    // 12. NO_SUGGESTIONS policy.
    check(runRules(document({ruleJson("1", "a", "b", false)}, true), "a",
                "com.example.app", true) == "a",
          "skip when NO_SUGGESTIONS");

    // 13. applyOncePerCommit keeps the tail intact.
    check(runRules(oneRule("a", "b", true), "a a a") == "b a a",
          "apply only first match");

    // 14. Disabled rules are ignored (compiled ruleset skips them).
    check(runRules(R"({"version":1,"rules":[{"id":"1","enabled":false,)"
                R"("name":"off","pattern":"a","replacement":"b",)"
                R"("applyOncePerCommit":false,"stopOnFirstMatch":false,)"
                R"("caseInsensitive":false}]})",
                "a") == "a",
          "disabled rule ignored");

    // 15. Empty input stays empty.
    check(runRules(oneRule("a", "b", false), "") == "", "empty input");

    if (failures == 0) {
        std::cout << "ALL TESTS PASSED\n";
        return 0;
    }
    std::cout << failures << " TEST(S) FAILED\n";
    return 1;
}

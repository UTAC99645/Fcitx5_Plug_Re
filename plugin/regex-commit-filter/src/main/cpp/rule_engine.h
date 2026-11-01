/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef REGEXCOMMIT_RULE_ENGINE_H
#define REGEXCOMMIT_RULE_ENGINE_H

#include <map>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include <re2/re2.h>

namespace regexcommit {

struct Scope {
    std::string mode = "blacklist";
    std::vector<std::string> packages;
};

struct Rule {
    std::string id;
    bool enabled = true;
    std::string name;
    std::string pattern;
    std::string replacement;
    Scope scope;
    bool applyOncePerCommit = true;
    bool stopOnFirstMatch = false;
    bool caseInsensitive = false;
    bool unescapeReplacement = false;
};

struct CompiledRule {
    Rule rule;
    std::shared_ptr<const re2::RE2> re;
};

struct RuleSet {
    bool skipNoSpellCheck = false;
    std::vector<std::shared_ptr<const CompiledRule>> rules;
};

struct Limits {
    size_t maxTextBytes = 4096;
    int maxReplacementsPerRule = 1000;
    long long maxNanosPerRule = 20000000LL; // 20 ms
};

struct CompileResult {
    std::shared_ptr<const RuleSet> ruleset;
    std::vector<std::string> errors;
};

/**
 * Parses the rules JSON and compiles every enabled pattern. Compilation only
 * happens when the plugin pushes a new ruleset, never on the commit hot path.
 */
class RuleCompiler {
public:
    CompileResult compileJson(const std::string &json);
    void clearCache();

private:
    std::shared_ptr<const re2::RE2> compilePattern(const std::string &pattern,
                                                   bool caseInsensitive);
    std::unordered_map<std::string, std::shared_ptr<const re2::RE2>> cache_;
};

/**
 * Applies the rules in order; the output of one rule feeds the next one.
 * Returns [input] unchanged on any limit violation or internal failure.
 */
std::string applyRules(const RuleSet &ruleset, const std::string &input,
                       const std::string &packageName, const Limits &limits,
                       bool noSpellCheck);

bool isValidUtf8(const std::string &text);

} // namespace regexcommit

#endif // REGEXCOMMIT_RULE_ENGINE_H

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include "rule_engine.h"

#include <absl/strings/string_view.h>

#include <chrono>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "nlohmann/json.hpp"

namespace regexcommit {

namespace {

constexpr size_t kMaxPatternLength = 4096;
constexpr size_t kMaxCachedPatterns = 256;

bool isUtf8Continuation(unsigned char c) { return (c & 0xC0) == 0x80; }

/** Offset of the next UTF-8 character after [pos]. */
size_t nextCharOffset(const std::string &text, size_t pos) {
    if (pos >= text.size()) {
        return text.size();
    }
    size_t next = pos + 1;
    while (next < text.size() &&
           isUtf8Continuation(static_cast<unsigned char>(text[next]))) {
        ++next;
    }
    return next;
}

bool scopeAllows(const Scope &scope, const std::string &packageName) {
    if (scope.packages.empty()) {
        return true;
    }
    bool hit = false;
    for (const auto &pkg : scope.packages) {
        if (!pkg.empty() && pkg == packageName) {
            hit = true;
            break;
        }
    }
    return scope.mode == "whitelist" ? hit : !hit;
}

void appendGroup(std::string &out, const std::vector<absl::string_view> &subs,
                 int index) {
    if (index < 0 || index >= static_cast<int>(subs.size())) {
        return;
    }
    if (subs[static_cast<size_t>(index)].data() == nullptr) {
        return;
    }
    out.append(subs[static_cast<size_t>(index)].data(),
               subs[static_cast<size_t>(index)].size());
}

std::string expandReplacement(
    const std::string &tpl, const std::vector<absl::string_view> &subs,
    const std::map<std::string, int> &named, bool unescape) {
    std::string out;
    out.reserve(tpl.size() + 16);
    size_t i = 0;
    while (i < tpl.size()) {
        const char c = tpl[i];
        if (c == '\\' && unescape) {
            if (i + 1 >= tpl.size()) {
                out.push_back('\\');
                ++i;
                continue;
            }
            switch (tpl[i + 1]) {
            case 'n':
                out.push_back('\n');
                break;
            case 'r':
                out.push_back('\r');
                break;
            case 't':
                out.push_back('\t');
                break;
            case '0':
                out.push_back('\0');
                break;
            case '\\':
                out.push_back('\\');
                break;
            default:
                out.push_back('\\');
                out.push_back(tpl[i + 1]);
                break;
            }
            i += 2;
            continue;
        }
        if (c != '$') {
            out.push_back(c);
            ++i;
            continue;
        }
        if (i + 1 >= tpl.size()) {
            out.push_back('$');
            ++i;
            continue;
        }
        const char next = tpl[i + 1];
        if (next == '$') {
            out.push_back('$');
            i += 2;
            continue;
        }
        if (next == '{') {
            const size_t end = tpl.find('}', i + 2);
            if (end == std::string::npos) {
                out.append(tpl, i, tpl.size() - i);
                break;
            }
            const std::string key = tpl.substr(i + 2, end - i - 2);
            const auto namedIt = named.find(key);
            if (namedIt != named.end()) {
                appendGroup(out, subs, namedIt->second);
            } else {
                const int index = std::atoi(key.c_str());
                if (!key.empty() && std::all_of(key.begin(), key.end(), [](char ch) {
                        return ch >= '0' && ch <= '9';
                    })) {
                    appendGroup(out, subs, index);
                }
            }
            i = end + 1;
            continue;
        }
        if (next >= '0' && next <= '9') {
            size_t j = i + 1;
            std::string digits;
            while (j < tpl.size() && tpl[j] >= '0' && tpl[j] <= '9' &&
                   digits.size() < 9) {
                digits.push_back(tpl[j]);
                ++j;
            }
            appendGroup(out, subs, std::atoi(digits.c_str()));
            i = j;
            continue;
        }
        out.push_back('$');
        ++i;
    }
    return out;
}

} // namespace

bool isValidUtf8(const std::string &text) {
    size_t i = 0;
    while (i < text.size()) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        if (c < 0x80) {
            ++i;
            continue;
        }
        int extra = 0;
        if ((c & 0xE0) == 0xC0) {
            extra = 1;
            if (c < 0xC2) {
                return false; // overlong
            }
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3;
            if (c > 0xF4) {
                return false;
            }
        } else {
            return false;
        }
        if (i + static_cast<size_t>(extra) >= text.size()) {
            return false;
        }
        for (int k = 1; k <= extra; ++k) {
            if (!isUtf8Continuation(static_cast<unsigned char>(text[i + static_cast<size_t>(k)]))) {
                return false;
            }
        }
        i += static_cast<size_t>(extra) + 1;
    }
    return true;
}

std::shared_ptr<const re2::RE2>
RuleCompiler::compilePattern(const std::string &pattern, bool caseInsensitive) {
    const std::string key =
        (caseInsensitive ? "i\u0001" : "s\u0001") + pattern;
    if (const auto it = cache_.find(key); it != cache_.end()) {
        return it->second;
    }
    re2::RE2::Options options;
    options.set_log_errors(false);
    options.set_case_sensitive(!caseInsensitive);
    options.set_encoding(re2::RE2::Options::EncodingUTF8);
    options.set_max_mem(2 * 1024 * 1024);
    auto re = std::make_shared<re2::RE2>(pattern, options);
    if (!re->ok()) {
        return nullptr;
    }
    if (cache_.size() >= kMaxCachedPatterns) {
        cache_.clear();
    }
    cache_.emplace(key, re);
    return re;
}

void RuleCompiler::clearCache() { cache_.clear(); }

CompileResult RuleCompiler::compileJson(const std::string &json) {
    CompileResult result;
    const auto doc = nlohmann::json::parse(json, nullptr, false);
    if (doc.is_discarded() || !doc.is_object()) {
        result.errors.emplace_back("invalid JSON");
        return result;
    }
    auto set = std::make_shared<RuleSet>();
    set->skipNoSpellCheck = doc.value("skipNoSpellCheck", false);
    const auto rulesIt = doc.find("rules");
    if (rulesIt != doc.end() && rulesIt->is_array()) {
        int index = 0;
        for (const auto &item : *rulesIt) {
            ++index;
            if (!item.is_object()) {
                continue;
            }
            Rule rule;
            rule.id = item.value("id", std::string());
            rule.enabled = item.value("enabled", true);
            rule.name = item.value("name", std::string());
            rule.pattern = item.value("pattern", std::string());
            rule.replacement = item.value("replacement", std::string());
            rule.applyOncePerCommit = item.value("applyOncePerCommit", true);
            rule.stopOnFirstMatch = item.value("stopOnFirstMatch", false);
            rule.caseInsensitive = item.value("caseInsensitive", false);
            rule.unescapeReplacement = item.value("unescapeReplacement", false);
            if (const auto scopeIt = item.find("scope");
                scopeIt != item.end() && scopeIt->is_object()) {
                rule.scope.mode = scopeIt->value("mode", std::string("blacklist"));
                if (const auto pkgs = scopeIt->find("packages");
                    pkgs != scopeIt->end() && pkgs->is_array()) {
                    for (const auto &pkg : *pkgs) {
                        if (pkg.is_string()) {
                            rule.scope.packages.push_back(pkg.get<std::string>());
                        }
                    }
                }
            }
            if (!rule.enabled) {
                continue;
            }
            const std::string label =
                rule.name.empty() ? ("#" + std::to_string(index)) : rule.name;
            if (rule.pattern.empty()) {
                result.errors.push_back("rule " + label + ": empty pattern");
                continue;
            }
            if (rule.pattern.size() > kMaxPatternLength) {
                result.errors.push_back("rule " + label + ": pattern too long");
                continue;
            }
            if (!isValidUtf8(rule.replacement)) {
                result.errors.push_back("rule " + label +
                                        ": replacement is not valid UTF-8");
                continue;
            }
            auto re = compilePattern(rule.pattern, rule.caseInsensitive);
            if (!re) {
                result.errors.push_back("rule " + label +
                                        ": invalid RE2 pattern");
                continue;
            }
            auto compiled = std::make_shared<CompiledRule>();
            compiled->rule = std::move(rule);
            compiled->re = std::move(re);
            set->rules.push_back(std::move(compiled));
        }
    }
    if (!result.errors.empty()) {
        return result;
    }
    result.ruleset = std::move(set);
    return result;
}

std::string applyRules(const RuleSet &ruleset, const std::string &input,
                       const std::string &packageName, const Limits &limits,
                       bool noSpellCheck) {
    if (input.empty() || input.size() > limits.maxTextBytes) {
        return input;
    }
    if (!isValidUtf8(input)) {
        return input;
    }
    if (ruleset.skipNoSpellCheck && noSpellCheck) {
        return input;
    }
    std::string text = input;
    for (const auto &compiled : ruleset.rules) {
        if (!compiled || !compiled->re) {
            continue;
        }
        const Rule &rule = compiled->rule;
        if (!rule.enabled || !scopeAllows(rule.scope, packageName)) {
            continue;
        }
        const auto started = std::chrono::steady_clock::now();
        const int nsub = compiled->re->NumberOfCapturingGroups() + 1;
        std::vector<absl::string_view> subs(static_cast<size_t>(nsub));
        std::string out;
        out.reserve(text.size() + 16);
        size_t copyPos = 0;
        size_t searchPos = 0;
        int count = 0;
        bool limitHit = false;
        while (searchPos <= text.size()) {
            if (!compiled->re->Match(absl::string_view(text), searchPos, text.size(),
                                     re2::RE2::UNANCHORED, subs.data(), nsub)) {
                break;
            }
            const size_t matchStart =
                static_cast<size_t>(subs[0].data() - text.data());
            const size_t matchEnd = matchStart + subs[0].size();
            if (count + 1 > limits.maxReplacementsPerRule) {
                limitHit = true;
                break;
            }
            out.append(text, copyPos, matchStart - copyPos);
            out += expandReplacement(rule.replacement, subs,
                                     compiled->re->NamedCapturingGroups(),
                                     rule.unescapeReplacement);
            copyPos = matchEnd;
            ++count;
            if (rule.applyOncePerCommit) {
                break;
            }
            if (std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now() - started)
                        .count() > limits.maxNanosPerRule) {
                limitHit = true;
                break;
            }
            if (matchStart == matchEnd) {
                searchPos = nextCharOffset(text, matchEnd);
                if (searchPos >= text.size()) {
                    break;
                }
            } else {
                searchPos = matchEnd;
            }
        }
        if (limitHit || count == 0) {
            continue;
        }
        out.append(text, copyPos, text.size() - copyPos);
        if (!isValidUtf8(out)) {
            continue;
        }
        const bool stopAfter = rule.stopOnFirstMatch;
        text = std::move(out);
        if (stopAfter) {
            break;
        }
    }
    return text;
}

} // namespace regexcommit

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include "regexcommitaddon.h"

#include "fcitx-utils/capabilityflags.h"
#include "fcitx/addonfactory.h"
#include "fcitx/addonmanager.h"
#include "fcitx/event.h"
#include "fcitx/inputcontext.h"

#include "nlohmann/json.hpp"

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>

namespace regexcommit {

namespace {
const char kSocketSuffix[] = ".regex_commit.rules";
const char kConfigSubdir[] = "regex-commit-filter";
const char kRulesFileName[] = "rules.json";
const char kFallbackPackage[] = "org.fcitx.fcitx5.android";
}

RegexCommitFilter::RegexCommitFilter(fcitx::Instance *instance)
    : instance_(instance) {
    limits_.maxTextBytes = 4096;
    limits_.maxReplacementsPerRule = 1000;
    limits_.maxNanosPerRule = 20000000LL;
    loadPersisted();
    server_ = std::make_unique<RuleSocketServer>(
        socketName(), [this](const std::string &request) {
            return handleRequest(request);
        });
    server_->start();
    commitWatcher_ = instance_->watchEvent(
        fcitx::EventType::InputContextCommitString,
        fcitx::EventWatcherPhase::PostInputMethod,
        [this](fcitx::Event &event) { handleCommit(event, false); });
    cursorWatcher_ = instance_->watchEvent(
        fcitx::EventType::InputContextCommitStringWithCursor,
        fcitx::EventWatcherPhase::PostInputMethod,
        [this](fcitx::Event &event) { handleCommit(event, true); });
}

RegexCommitFilter::~RegexCommitFilter() {
    commitWatcher_.reset();
    cursorWatcher_.reset();
    if (server_) {
        server_->stop();
        server_.reset();
    }
}

void RegexCommitFilter::handleCommit(fcitx::Event &event, bool withCursor) {
    if (inRewrite_ || event.accepted()) {
        return;
    }
    auto *ic = static_cast<fcitx::InputContextEvent &>(event).inputContext();
    if (!ic) {
        return;
    }
    auto rules = currentRuleset();
    if (!rules || rules->rules.empty()) {
        return;
    }
    try {
        const std::string &text = withCursor
            ? static_cast<fcitx::CommitStringWithCursorEvent &>(event).text()
            : static_cast<fcitx::CommitStringEvent &>(event).text();
        if (text.empty()) {
            return;
        }
        const auto flags = ic->capabilityFlags();
        // Never rewrite password fields or fields marked sensitive.
        if (flags.test(fcitx::CapabilityFlag::Password) ||
            flags.test(fcitx::CapabilityFlag::Sensitive)) {
            return;
        }
        const bool noSpellCheck = flags.test(fcitx::CapabilityFlag::NoSpellCheck);
        const std::string rewritten =
            applyRules(*rules, text, ic->program(), limits_, noSpellCheck);
        if (rewritten == text) {
            return;
        }
        // Suppress the original commit, then commit the rewritten text. The
        // re-entrant event is ignored through inRewrite_.
        event.accept();
        inRewrite_ = true;
        try {
            ic->commitString(rewritten);
        } catch (...) {
        }
        inRewrite_ = false;
    } catch (...) {
        inRewrite_ = false;
    }
}

std::shared_ptr<const RuleSet> RegexCommitFilter::currentRuleset() {
    std::lock_guard<std::mutex> lock(mutex_);
    return ruleset_;
}

void RegexCommitFilter::installRuleset(std::shared_ptr<const RuleSet> ruleset) {
    std::lock_guard<std::mutex> lock(mutex_);
    ruleset_ = std::move(ruleset);
}

std::string RegexCommitFilter::handleRequest(const std::string &request) {
    const auto doc = nlohmann::json::parse(request, nullptr, false);
    if (doc.is_discarded() || !doc.is_object()) {
        return "{\"ok\":false,\"errors\":[\"invalid request\"]}";
    }
    if (const auto op = doc.find("op"); op != doc.end() && op->is_string() &&
                                          op->get<std::string>() == "ping") {
        return "{\"ok\":true}";
    }
    auto result = compiler_.compileJson(request);
    if (!result.ruleset) {
        nlohmann::json reply;
        reply["ok"] = false;
        reply["errors"] = result.errors;
        return reply.dump();
    }
    installRuleset(result.ruleset);
    persist(request);
    return "{\"ok\":true}";
}

std::string RegexCommitFilter::socketName() const {
    std::string package = kFallbackPackage;
    std::ifstream in("/proc/self/cmdline", std::ios::binary);
    if (in) {
        std::string raw((std::istreambuf_iterator<char>(in)),
                        std::istreambuf_iterator<char>());
        const size_t nul = raw.find('\0');
        const std::string cmdline = raw.substr(0, nul);
        const size_t colon = cmdline.find(':');
        const std::string base =
            colon == std::string::npos ? cmdline : cmdline.substr(0, colon);
        if (!base.empty()) {
            package = base;
        }
    }
    return package + kSocketSuffix;
}

std::string RegexCommitFilter::configDir() const {
    const char *configHome = std::getenv("FCITX_CONFIG_HOME");
    std::string base;
    if (configHome && *configHome) {
        base = configHome;
    } else {
        const char *home = std::getenv("HOME");
        base = home && *home ? std::string(home) + "/.config"
                             : std::string("/tmp");
    }
    return base + "/" + kConfigSubdir;
}

void RegexCommitFilter::loadPersisted() {
    try {
        std::ifstream in(configDir() + "/" + kRulesFileName, std::ios::binary);
        if (!in) {
            return;
        }
        const std::string json((std::istreambuf_iterator<char>(in)),
                               std::istreambuf_iterator<char>());
        auto result = compiler_.compileJson(json);
        if (result.ruleset) {
            installRuleset(std::move(result.ruleset));
        }
    } catch (...) {
    }
}

void RegexCommitFilter::persist(const std::string &json) {
    try {
        const std::string dir = configDir();
        std::filesystem::create_directories(dir);
        const std::string path = dir + "/" + kRulesFileName;
        const std::string tmp = path + ".tmp";
        {
            std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
            if (!out) {
                return;
            }
            out.write(json.data(), static_cast<std::streamsize>(json.size()));
            out.flush();
        }
        std::filesystem::rename(tmp, path);
    } catch (...) {
    }
}

} // namespace regexcommit

namespace fcitx {

class RegexCommitFilterFactory : public AddonFactory {
    AddonInstance *create(AddonManager *manager) override {
        return new regexcommit::RegexCommitFilter(manager->instance());
    }
};

} // namespace fcitx

FCITX_ADDON_FACTORY_V2(regexcommit, fcitx::RegexCommitFilterFactory);

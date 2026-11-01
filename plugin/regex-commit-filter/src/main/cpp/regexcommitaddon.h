/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef REGEXCOMMIT_ADDON_H
#define REGEXCOMMIT_ADDON_H

#include "fcitx/addoninstance.h"
#include "fcitx/instance.h"
#include "rule_engine.h"
#include "rulesocket.h"

#include <memory>
#include <mutex>
#include <string>

namespace regexcommit {

class RegexCommitFilter : public fcitx::AddonInstance {
public:
    explicit RegexCommitFilter(fcitx::Instance *instance);
    ~RegexCommitFilter() override;

    RegexCommitFilter(const RegexCommitFilter &) = delete;
    RegexCommitFilter &operator=(const RegexCommitFilter &) = delete;

private:
    void handleCommit(fcitx::Event &event, bool withCursor);
    std::shared_ptr<const RuleSet> currentRuleset();
    void installRuleset(std::shared_ptr<const RuleSet> ruleset);

    void loadPersisted();
    void persist(const std::string &json);

    std::string handleRequest(const std::string &request);
    std::string socketName() const;
    std::string configDir() const;

    fcitx::Instance *instance_;
    RuleCompiler compiler_;
    std::mutex mutex_;
    std::shared_ptr<const RuleSet> ruleset_;
    Limits limits_;
    std::unique_ptr<RuleSocketServer> server_;
    std::unique_ptr<fcitx::HandlerTableEntry<fcitx::EventHandler>> commitWatcher_;
    std::unique_ptr<fcitx::HandlerTableEntry<fcitx::EventHandler>> cursorWatcher_;
    bool inRewrite_ = false;
};

} // namespace regexcommit

#endif // REGEXCOMMIT_ADDON_H

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef REGEXCOMMIT_RULE_SOCKET_H
#define REGEXCOMMIT_RULE_SOCKET_H

#include <atomic>
#include <functional>
#include <string>
#include <thread>

namespace regexcommit {

/**
 * Minimal one-shot-per-connection server on an abstract AF_UNIX socket.
 * Requests and replies are JSON payloads.
 *
 * The same name is bound twice, once with the exact abstract-name length and
 * once with the full sizeof(sockaddr_un): android.net.LocalSocket does not
 * document which length it passes, and the kernel treats trailing NULs as part
 * of the abstract name. Binding both makes the client work either way.
 *
 * The accept loop uses poll() plus a self-pipe so stop() always returns
 * promptly, even if the addon is destroyed while the IME is quitting.
 */
class RuleSocketServer {
public:
    using Handler = std::function<std::string(const std::string &request)>;

    RuleSocketServer(std::string socketName, Handler handler);
    ~RuleSocketServer();

    RuleSocketServer(const RuleSocketServer &) = delete;
    RuleSocketServer &operator=(const RuleSocketServer &) = delete;

    bool start();
    void stop();

private:
    void run(int wakeFd);
    void handleConnection(int clientFd);

    std::string socketName_;
    Handler handler_;
    int fds_[2] = {-1, -1};
    int wakePipe_[2] = {-1, -1};
    std::atomic<bool> running_{false};
    std::thread thread_;
};

} // namespace regexcommit

#endif // REGEXCOMMIT_RULE_SOCKET_H

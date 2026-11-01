/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include "rulesocket.h"

#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstring>
#include <string>
#include <utility>

#include <fcntl.h>
#include <poll.h>

namespace regexcommit {

namespace {
constexpr size_t kMaxRequestBytes = 512 * 1024;
constexpr size_t kMaxSockName = sizeof(((sockaddr_un *)nullptr)->sun_path) - 1;
constexpr int kPollTimeoutMs = 200;
constexpr int kIoTimeoutMs = 1000;

int bindAbstract(const std::string &name, size_t addrLen) {
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    // sun_path[0] stays '\0': the name lives in the abstract namespace, so
    // nothing is created on the filesystem.
    std::memcpy(addr.sun_path + 1, name.data(), name.size());
    if (::bind(fd, reinterpret_cast<sockaddr *>(&addr),
               static_cast<int>(addrLen)) < 0 ||
        ::listen(fd, 4) < 0) {
        ::close(fd);
        return -1;
    }
    // accept() must never block, even if the client vanished between poll()
    // and accept().
    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
    return fd;
}
} // namespace

RuleSocketServer::RuleSocketServer(std::string socketName, Handler handler)
    : socketName_(std::move(socketName)), handler_(std::move(handler)) {}

RuleSocketServer::~RuleSocketServer() { stop(); }

bool RuleSocketServer::start() {
    if (running_.load()) {
        return true;
    }
    if (socketName_.empty() || socketName_.size() > kMaxSockName) {
        return false;
    }
    if (::pipe2(wakePipe_, O_CLOEXEC | O_NONBLOCK) < 0) {
        return false;
    }

    // 1) exact abstract name length: offsetof + leading NUL + name
    fds_[0] = bindAbstract(
        socketName_, offsetof(sockaddr_un, sun_path) + 1 + socketName_.size());
    // 2) full sockaddr_un length: the trailing bytes become part of the name.
    fds_[1] = bindAbstract(socketName_, sizeof(sockaddr_un));

    if (fds_[0] < 0 && fds_[1] < 0) {
        ::close(wakePipe_[0]);
        ::close(wakePipe_[1]);
        wakePipe_[0] = wakePipe_[1] = -1;
        return false;
    }

    running_.store(true);
    const int wakeFd = wakePipe_[0];
    thread_ = std::thread([this, wakeFd] { run(wakeFd); });
    return true;
}

void RuleSocketServer::stop() {
    if (!running_.exchange(false)) {
        return;
    }
    if (wakePipe_[1] >= 0) {
        char token = 'x';
        ::write(wakePipe_[1], &token, 1);
    }
    if (thread_.joinable()) {
        thread_.join();
    }
    for (int &fd : fds_) {
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
    }
    for (int &pipeFd : wakePipe_) {
        if (pipeFd >= 0) {
            ::close(pipeFd);
            pipeFd = -1;
        }
    }
}

void RuleSocketServer::run(int wakeFd) {
    while (running_.load()) {
        pollfd fds[3];
        fds[0] = {fds_[0], POLLIN, 0};
        fds[1] = {fds_[1], POLLIN, 0};
        fds[2] = {wakeFd, POLLIN, 0};
        const int ready = ::poll(fds, 3, kPollTimeoutMs);
        if (ready <= 0) {
            continue;
        }
        if ((fds[2].revents & POLLIN) != 0) {
            break;
        }
        for (int i = 0; i < 2; ++i) {
            if ((fds[i].revents & (POLLIN | POLLERR | POLLHUP)) == 0) {
                continue;
            }
            const int client = ::accept(fds[i].fd, nullptr, nullptr);
            if (client < 0) {
                if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK ||
                    errno == ECONNABORTED) {
                    continue;
                }
                break;
            }
            handleConnection(client);
            ::close(client);
        }
    }
}

void RuleSocketServer::handleConnection(int clientFd) {
    timeval timeout{};
    timeout.tv_sec = kIoTimeoutMs / 1000;
    timeout.tv_usec = (kIoTimeoutMs % 1000) * 1000;
    ::setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ::setsockopt(clientFd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    std::string request;
    request.reserve(4096);
    char buffer[8192];
    while (request.size() < kMaxRequestBytes) {
        const ssize_t read = ::recv(clientFd, buffer, sizeof(buffer), 0);
        if (read <= 0) {
            break;
        }
        request.append(buffer, static_cast<size_t>(read));
    }
    std::string reply;
    try {
        reply = handler_ ? handler_(request) : std::string();
    } catch (...) {
        reply.clear();
    }
    if (reply.empty()) {
        reply = "{\"ok\":false}";
    }
    size_t written = 0;
    while (written < reply.size()) {
        const ssize_t sent = ::send(clientFd, reply.data() + written,
                                    reply.size() - written, 0);
        if (sent <= 0) {
            break;
        }
        written += static_cast<size_t>(sent);
    }
}

} // namespace regexcommit

import Foundation
import Combine

/// A remote-shell reply: exit code plus the phone's stdout and stderr.
struct ShellReply {
    let code: Int
    let out: String
    let err: String
}

/// One row of the phone's `apps` command.
struct AppRow: Identifiable {
    let pkg: String
    let label: String
    let version: String
    let uid: String
    let kind: String
    let state: String
    var id: String { pkg }
    var isSystem: Bool { kind == "system" }
}

/// One row of the phone's `ps` command.
struct ProcRow: Identifiable {
    let pid: String
    let user: String
    let name: String
    var id: String { pid + "/" + name }
}

/// The shell session on port 7003: same handshake and AES-GCM channel as a video
/// session, but it carries commands instead of frames.
@MainActor
final class ShellModel: ObservableObject {
    let phone: PairedPhone

    @Published private(set) var lines: [String] = []
    @Published private(set) var status = "Connecting…"
    @Published private(set) var ready = false
    @Published private(set) var busy = false
    @Published private(set) var apps: [AppRow] = []
    @Published private(set) var processes: [ProcRow] = []
    @Published private(set) var progress: Double?
    @Published private(set) var deviceInfo = ""

    private var stream: TCPStream?
    private var channel: SecureChannel?
    private var task: Task<Void, Never>?
    private var receiver: Task<Void, Never>?
    private var execWaiters: [Int: CheckedContinuation<ShellReply, Error>] = [:]
    private var apkWaiters: [Int: CheckedContinuation<(String, String), Error>] = [:]
    private var nextId = 0

    init(phone: PairedPhone) {
        self.phone = phone
    }

    // MARK: Lifecycle

    func connect() {
        guard task == nil else { return }
        task = Task { await run() }
    }

    func disconnect() {
        receiver?.cancel()
        task?.cancel()
        task = nil
        receiver = nil
        channel = nil
        stream?.close()
        stream = nil
        ready = false
        failAll()
    }

    private func run() async {
        do {
            status = "Waiting for the phone to allow this connection…"
            let (stream, channel) = try await SessionClient.open(phone: phone, port: Proto.shellPort)
            self.stream = stream
            self.channel = channel
            ready = true
            status = "Connected to \(phone.name)"
            append("connected to \(phone.name) — type help")
            receiver = Task { await receiveLoop(channel) }
            await runCommand("device")
        } catch {
            if !Task.isCancelled {
                ready = false
                status = error.localizedDescription
                append("! \(error.localizedDescription)")
                failAll()
            }
        }
    }

    private func receiveLoop(_ channel: SecureChannel) async {
        do {
            while !Task.isCancelled {
                let message = try await channel.receive()
                switch message.type {
                case Proto.Msg.control:
                    handle(message.payload)
                case Proto.Msg.bye:
                    throw WireError.closed
                default:
                    break
                }
            }
        } catch {
            if !Task.isCancelled {
                ready = false
                status = (error as? WireError).map { $0.localizedDescription } ?? "Disconnected"
                append("! \(status)")
                failAll()
            }
        }
    }

    private func handle(_ payload: Data) {
        guard let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
              let kind = object["t"] as? String,
              let id = (object["id"] as? NSNumber)?.intValue else { return }

        switch kind {
        case "shell":
            guard let waiter = execWaiters.removeValue(forKey: id) else { return }
            waiter.resume(returning: ShellReply(
                code: (object["code"] as? NSNumber)?.intValue ?? 0,
                out: object["out"] as? String ?? "",
                err: object["err"] as? String ?? ""
            ))
        case "apk-status":
            guard let waiter = apkWaiters.removeValue(forKey: id) else { return }
            waiter.resume(returning: (
                object["state"] as? String ?? "unknown",
                object["message"] as? String ?? ""
            ))
        default:
            break
        }
    }

    private func failAll() {
        for (_, waiter) in execWaiters { waiter.resume(throwing: WireError.closed) }
        execWaiters.removeAll()
        for (_, waiter) in apkWaiters { waiter.resume(throwing: WireError.closed) }
        apkWaiters.removeAll()
        progress = nil
        busy = false
    }

    // MARK: Commands

    func append(_ line: String) {
        if lines.count > 400 { lines.removeFirst(lines.count - 400) }
        lines.append(line)
    }

    @discardableResult
    func runCommand(_ command: String) async -> ShellReply {
        append("$ \(command)")
        busy = true
        let reply = await exec(command)
        busy = false
        for line in reply.out.split(separator: "\n", omittingEmptySubsequences: false) where !line.isEmpty {
            append(String(line))
        }
        for line in reply.err.split(separator: "\n", omittingEmptySubsequences: false) where !line.isEmpty {
            append("! " + line)
        }
        if reply.code != 0 && reply.err.isEmpty {
            append("! exit \(reply.code)")
        }
        applyTables(command: command, out: reply.out)
        return reply
    }

    private func exec(_ command: String) async -> ShellReply {
        guard let channel else { return ShellReply(code: 1, out: "", err: "not connected") }
        let id = nextId
        nextId += 1
        do {
            return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<ShellReply, Error>) in
                execWaiters[id] = cont
                do {
                    try send(["t": "exec", "id": id, "cmd": command], on: channel)
                } catch {
                    execWaiters.removeValue(forKey: id)
                    cont.resume(throwing: error)
                }
            }
        } catch {
            return ShellReply(code: 1, out: "", err: error.localizedDescription)
        }
    }

    private func send(_ object: [String: Any], on channel: SecureChannel) throws {
        let payload = try JSONSerialization.data(withJSONObject: object)
        try channel.send(type: Proto.Msg.control, payload: payload)
    }

    private func applyTables(command: String, out: String) {
        switch command.split(separator: " ").first.map(String.init) ?? "" {
        case "apps":
            apps = out.split(separator: "\n").compactMap { line -> AppRow? in
                let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
                guard f.count >= 6 else { return nil }
                return AppRow(pkg: f[0], label: f[1], version: f[2], uid: f[3], kind: f[4], state: f[5])
            }.sorted { $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending }
        case "ps":
            processes = out.split(separator: "\n").compactMap { line -> ProcRow? in
                let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
                guard f.count >= 3 else { return nil }
                return ProcRow(pid: f[0], user: f[1], name: f[2])
            }
        case "device":
            deviceInfo = out
        default:
            break
        }
    }

    // MARK: APK push

    /// Sends an APK over the session. Android installs it after the owner taps its dialog.
    func pushApk(url: URL) {
        Task { await upload(url: url) }
    }

    private func upload(url: URL) async {
        guard let channel else {
            append("! not connected")
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        do {
            let data = try Data(contentsOf: url)
            let name = url.lastPathComponent
            append("$ push \(name) — \(data.count) bytes")
            busy = true
            progress = 0

            let id = nextId
            nextId += 1
            try send(["t": "apk", "id": id, "name": name, "size": data.count], on: channel)

            let chunk = 48 * 1024
            var offset = 0
            while offset < data.count {
                let end = min(offset + chunk, data.count)
                let piece = data.subdata(in: offset..<end).base64EncodedString()
                try send(["t": "apk-chunk", "id": id, "data": piece], on: channel)
                offset = end
                progress = Double(offset) / Double(max(data.count, 1))
                await Task.yield()
            }
            try send(["t": "apk-end", "id": id], on: channel)

            let (state, message) = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<(String, String), Error>) in
                apkWaiters[id] = cont
            }
            append("apk: \(state) \(message)".trimmingCharacters(in: .whitespaces))
            progress = nil
            busy = false
        } catch {
            append("! push failed: \(error.localizedDescription)")
            progress = nil
            busy = false
        }
    }
}

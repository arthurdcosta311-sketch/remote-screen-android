#if ADB_MOBILE
import Foundation
import Combine
import Darwin

/// One finished adb command line.
struct AdbResult {
    let code: Int32
    let output: String
    var ok: Bool { code == 0 }
    var text: String {
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? (ok ? "ok" : "exit \(code)") : trimmed
    }
}

/// The phone reports transport changes through this weak symbol; adb-mobile
/// leaves it to the host app to define.
@_cdecl("adb_connect_status_updated")
public func adbConnectStatusUpdated(_ serial: UnsafePointer<CChar>?, _ status: UnsafePointer<CChar>?) {
    let serialText = serial.map { String(cString: $0) } ?? "?"
    let statusText = status.map { String(cString: $0) } ?? "?"
    AdbHost.shared.noteStatus(serial: serialText, status: statusText)
}

/// Bridges the adb-mobile library (libadb.a) into the app.
///
/// This is real adb running on the iPhone: it pairs with the phone's wireless
/// debugging over SPAKE2 + TLS, then opens `shell:` streams as the phone's
/// `shell` user — which is why force-stop, pm disable-user and reboot work here
/// without root or a device-owner install.
///
/// adb keeps process-wide state and is not reentrant, so every command goes
/// through one serial queue.
final class AdbHost: ObservableObject {
    static let shared = AdbHost()

    @Published private(set) var status = "adb not started"
    @Published private(set) var serial: String?
    @Published private(set) var connected = false

    private let queue = DispatchQueue(label: "dev.remotescreen.adb")
    private var prepared = false
    private final class Box { var result: AdbResult? }

    private init() {}

    // MARK: Setup

    private func prepare() {
        guard !prepared else { return }
        prepared = true
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let home = documents.appendingPathComponent("adb", isDirectory: true)
        try? FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        setenv("HOME", home.path, 1)
        setenv("ANDROID_ADB_SERVER_PORT", "15037", 1)
        queue.sync { _ = AdbHost.execute(["start-server"]) }
    }

    // MARK: Commands

    func run(_ arguments: [String]) async -> AdbResult {
        prepare()
        return await withCheckedContinuation { continuation in
            queue.async {
                continuation.resume(returning: AdbHost.execute(arguments))
            }
        }
    }

    func pair(host: String, pairingPort: String, code: String, connectPort: String) async -> AdbResult {
        let target = "\(host):\(pairingPort)"
        setStatus("pairing with \(target)…")
        let result = await run(["pair", target, code])
        if result.ok {
            setStatus("paired — connecting")
            if !connectPort.isEmpty {
                let connect = await run(["connect", "\(host):\(connectPort)"])
                setStatus(connect.text)
            } else {
                setStatus("paired — enter the connect port to open the shell")
            }
        } else {
            setStatus(result.text)
        }
        await refreshDevices()
        return result
    }

    func connect(host: String, port: String) async -> AdbResult {
        setStatus("connecting to \(host):\(port)…")
        let result = await run(["connect", "\(host):\(port)"])
        setStatus(result.text)
        await refreshDevices()
        return result
    }

    func disconnect() async -> AdbResult {
        let result = await run(["disconnect"])
        setStatus(result.text)
        await refreshDevices()
        return result
    }

    @discardableResult
    func refreshDevices() async -> [String] {
        let result = await run(["devices"])
        let names = result.output
            .split(separator: "\n")
            .dropFirst()
            .compactMap { line -> String? in
                let parts = line.split(separator: "\t").map { $0.trimmingCharacters(in: .whitespaces) }
                guard parts.count >= 2, parts[1] == "device" else { return nil }
                return parts[0]
            }
        let first = names.first
        DispatchQueue.main.async {
            self.serial = first
            self.connected = first != nil
        }
        return names
    }

    /// Runs a command on the phone through `adb shell`.
    func shell(_ command: String) async -> AdbResult {
        let parts = command.split(separator: " ").map(String.init)
        guard !parts.isEmpty else { return AdbResult(code: 1, output: "empty command") }
        return await run(["shell"] + parts)
    }

    /// Pushes an APK from this app's storage and installs it — no tap on the phone.
    func install(apk url: URL) async -> AdbResult {
        setStatus("installing \(url.lastPathComponent)…")
        let result = await run(["install", "-r", url.path])
        setStatus(result.text)
        return result
    }

    func reboot() async -> AdbResult {
        let result = await run(["reboot"])
        setStatus(result.text)
        return result
    }

    // MARK: State

    func noteStatus(serial: String, status: String) {
        let update = { self.status = "\(serial): \(status)" }
        if Thread.isMainThread { update() } else { DispatchQueue.main.async(execute: update) }
        if status.contains("device") {
            DispatchQueue.main.async { self.connected = true }
        }
    }

    private func setStatus(_ text: String) {
        if Thread.isMainThread { status = text } else { DispatchQueue.main.async { self.status = text } }
    }

    // MARK: Native

    /// adb runs the command on its own thread: some paths call pthread_exit
    /// instead of returning, so a missing answer falls back to its last output.
    private static func execute(_ arguments: [String]) -> AdbResult {
        let box = Box()
        let thread = Thread {
            var allocated: [UnsafeMutablePointer<CChar>?] = []
            var pointers: [UnsafePointer<CChar>?] = []
            for argument in arguments {
                let copy = strdup(argument)
                allocated.append(copy)
                pointers.append(copy.map { UnsafePointer($0) })
            }
            defer { for pointer in allocated { free(pointer) } }

            var message: UnsafeMutablePointer<CChar>?
            let count = Int32(pointers.count)
            let code = pointers.withUnsafeMutableBufferPointer { buffer in
                adb_commandline_porting(count, buffer.baseAddress, &message)
            }

            var text = ""
            if let message {
                text = String(cString: message)
                free(message)
            }
            box.result = AdbResult(code: code, output: text)
        }
        thread.stackSize = 2 << 20
        thread.start()

        let deadline = Date().addingTimeInterval(60)
        while box.result == nil && !thread.isFinished && Date() < deadline {
            usleep(50_000)
        }
        if let result = box.result { return result }

        var text = "adb did not return"
        if let last = adb_commandline_last_output() {
            let value = String(cString: last)
            free(last)
            if !value.isEmpty { text = value }
        }
        return AdbResult(code: -1, output: text)
    }
}
#endif

#if ADB_MOBILE
import SwiftUI
import UniformTypeIdentifiers

/// Real adb from the iPhone: pair with the phone's wireless debugging, then run
/// `shell` commands as the phone's `shell` user.
///
/// Pairing needs two things from the phone — its wireless-debugging screen open,
/// and the ports adbd is listening on. The receiver asks for both over the remote
/// shell on port 7003 (`wireless` and `adbports`), so the only thing typed here is
/// the six digits Android itself shows.
struct AdbView: View {
    let phone: PairedPhone
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var adb = AdbHost.shared

    /// The console session to the phone, used only to drive its screen and to read
    /// adbd's ports.
    @StateObject private var link: ShellModel

    @State private var pairPort = ""
    @State private var code = ""
    @State private var connectPort = ""
    @State private var baseline: Set<Int> = []
    @State private var scanNote = ""
    @State private var package = ""
    @State private var command = ""
    @State private var log: [String] = []
    @State private var busy = false
    @State private var showImport = false

    init(phone: PairedPhone) {
        self.phone = phone
        _link = StateObject(wrappedValue: ShellModel(phone: phone))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("The receiver does the pairing; the phone only has to show its own screen. Start Remote shell on the phone first, then tap step 1.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("1. Open Wireless debugging on the phone") { prepare() }
                        .disabled(busy || !link.ready)
                    Button("2. Scan for the pairing port") { scan() }
                        .disabled(busy || !link.ready)
                    if !scanNote.isEmpty {
                        Text(scanNote).font(.footnote).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Set up on the phone")
                } footer: {
                    Text(link.ready ? "Connected to \(phone.name)." : link.status)
                        .font(.footnote)
                }

                Section {
                    TextField("Pairing port", text: $pairPort)
                        .keyboardType(.numberPad)
                    TextField("Six digits shown on the phone", text: $code)
                        .keyboardType(.numberPad)
                    Button("Pair") { pair() }
                        .disabled(busy || code.isEmpty)
                } header: {
                    Text("Pair")
                } footer: {
                    Text("Only the six digits are typed by hand. If the port above is wrong, Pair tries the phone's other open ports itself.")
                        .font(.footnote)
                }

                Section {
                    TextField("Connect port", text: $connectPort)
                        .keyboardType(.numberPad)
                    Button("Connect") { connect() }
                        .disabled(busy || connectPort.isEmpty)
                    Button("Disconnect") { run(["disconnect"]) }
                } header: {
                    Text("Connect")
                } footer: {
                    Text("Filled in by step 2: the port adbd keeps open all the time.")
                        .font(.footnote)
                }

                Section {
                    Text(adb.status)
                        .font(.system(size: 12, design: .monospaced))
                } header: {
                    Text("Status")
                }

                Section {
                    TextField("pm disable-user --user 0 com.example.app", text: $command)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 12, design: .monospaced))
                    Button("Run") { shell(command) }
                        .disabled(busy || command.isEmpty)
                } header: {
                    Text("adb shell")
                }

                Section {
                    TextField("package (com.example.app)", text: $package)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 12, design: .monospaced))

                    HStack {
                        quick("Force stop") { shell("am force-stop \(package)") }
                        quick("Disable") { shell("pm disable-user --user 0 \(package)") }
                    }
                    HStack {
                        quick("Enable") { shell("pm enable \(package)") }
                        quick("Uninstall") { shell("pm uninstall --user 0 \(package)") }
                    }
                    HStack {
                        quick("Start") { shell("monkey -p \(package) -c android.intent.category.LAUNCHER 1") }
                        quick("My apps") { shell("pm list packages -3") }
                    }
                    Button("Rotate 90°") {
                        shell("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
                    }
                    Button("Reboot") { reboot() }
                    Button("Install APK…") { showImport = true }
                        .disabled(busy)
                } header: {
                    Text("Quick actions")
                } footer: {
                    Text("These run as the phone's shell user, so they need no root and no device-owner install.")
                        .font(.footnote)
                }

                Section {
                    ScrollView {
                        Text(log.isEmpty ? "no output yet" : log.joined(separator: "\n"))
                            .font(.system(size: 11, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 120)
                } header: {
                    HStack {
                        Text("Output")
                        Spacer()
                        if busy { ProgressView() }
                    }
                }
            }
            .navigationTitle("ADB · \(phone.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .fileImporter(
                isPresented: $showImport,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { install(url: url) }
                case .failure(let error):
                    append("! \(error.localizedDescription)")
                }
            }
            .onAppear {
                busy = false
                link.connect()
            }
            .onDisappear { link.disconnect() }
        }
    }

    // MARK: Preparing the phone

    /// Asks the phone which ports adbd has open now, then opens its
    /// wireless-debugging screen. This first scan is the baseline that makes the
    /// pairing port recognisable later: it is the port that appears only while
    /// Android's pairing dialog is showing.
    private func prepare() {
        busy = true
        Task {
            let first = await link.runCommand("adbports")
            let ports = Self.parsePorts(first.out)
            await MainActor.run {
                if !ports.isEmpty {
                    baseline = Set(ports)
                    connectPort = String(ports[ports.count - 1])
                }
            }
            _ = await link.runCommand("wireless")
            await MainActor.run {
                scanNote = ports.isEmpty
                    ? "No port answered yet — switch Wireless debugging on in the phone's Developer options, then tap step 2."
                    : "On the phone, switch Wireless debugging on and tap \"Pair device with pairing code\". Then tap step 2."
                busy = false
            }
        }
    }

    /// Second scan: a port that was not open before is the pairing port.
    private func scan() {
        busy = true
        Task {
            let reply = await link.runCommand("adbports")
            let ports = Self.parsePorts(reply.out)
            await MainActor.run {
                apply(ports)
                busy = false
            }
        }
    }

    private func apply(_ ports: [Int]) {
        guard !ports.isEmpty else {
            scanNote = "No port answered. Switch Wireless debugging on in the phone's Developer options."
            return
        }
        let current = Set(ports)
        let fresh = current.subtracting(baseline)
        if let pairing = fresh.min() {
            pairPort = String(pairing)
            if let other = ports.last(where: { $0 != pairing }) {
                connectPort = String(other)
            }
            scanNote = "Pairing port \(pairing) found. Type the six digits the phone shows, then tap Pair."
            return
        }
        if ports.count == 1 {
            connectPort = String(ports[0])
            scanNote = "Only the connect port is open. Tap \"Pair device with pairing code\" on the phone, then scan again."
            return
        }
        // Both ports were open before the dialog, so their order is unknown.
        if let last = ports.last { connectPort = String(last) }
        if pairPort.isEmpty, let first = ports.first { pairPort = String(first) }
        scanNote = "Ports \(ports.map(String.init).joined(separator: ", ")) are open. Pairing tries each one until Android accepts, so just type the digits."
    }

    private static func parsePorts(_ output: String) -> [Int] {
        for line in output.split(separator: "\n") where line.hasPrefix("ports") {
            return line.dropFirst("ports".count)
                .split(separator: " ")
                .compactMap { Int($0) }
        }
        return []
    }

    // MARK: Actions

    /// Pairs on the port believed to be the pairing one, then on the phone's other
    /// open ports, so a wrong guess costs nothing.
    private func pair() {
        busy = true
        Task {
            var candidates: [String] = []
            if !pairPort.isEmpty { candidates.append(pairPort) }
            for port in [connectPort] where !port.isEmpty && !candidates.contains(port) {
                candidates.append(port)
            }
            var lastResult: AdbResult?
            var paired = false
            for candidate in candidates {
                let result = await adb.pair(
                    host: phone.host,
                    pairingPort: candidate,
                    code: code,
                    connectPort: connectPort
                )
                lastResult = result
                if result.ok {
                    pairPort = candidate
                    paired = true
                    break
                }
            }
            let result = lastResult ?? AdbResult(code: 1, output: "no port to pair on")
            await MainActor.run {
                record(result)
                if paired {
                    scanNote = "Paired. adb is connected on port \(connectPort)."
                } else {
                    scanNote = "Pairing failed. Check that the six digits are still on the phone's screen — Android changes them every time the dialog opens."
                }
                busy = false
            }
        }
    }

    private func connect() {
        busy = true
        Task {
            let result = await adb.connect(host: phone.host, port: connectPort)
            await MainActor.run {
                record(result)
                busy = false
            }
        }
    }

    private func shell(_ line: String) {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        busy = true
        Task {
            append("$ \(trimmed)")
            let result = await adb.shell(trimmed)
            await MainActor.run {
                record(result)
                busy = false
            }
        }
    }

    private func reboot() {
        busy = true
        Task {
            let result = await adb.reboot()
            await MainActor.run {
                record(result)
                busy = false
            }
        }
    }

    private func run(_ arguments: [String]) {
        busy = true
        Task {
            let result = await adb.run(arguments)
            await MainActor.run {
                record(result)
                busy = false
            }
        }
    }

    private func install(url: URL) {
        busy = true
        Task {
            guard let copy = copyIntoDocuments(url) else {
                await MainActor.run {
                    append("! could not read the file")
                    busy = false
                }
                return
            }
            await MainActor.run { append("$ install \(copy.lastPathComponent)") }
            let result = await adb.install(apk: copy)
            await MainActor.run {
                record(result)
                busy = false
            }
        }
    }

    /// adb reads the APK from this app's storage, so a picked file is copied first.
    private func copyIntoDocuments(_ url: URL) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let directory = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("adb-uploads", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(url.lastPathComponent)
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.copyItem(at: url, to: destination)
            return destination
        } catch {
            return nil
        }
    }

    private func record(_ result: AdbResult) {
        append(result.text)
    }

    private func append(_ line: String) {
        log.append(line)
        if log.count > 300 { log.removeFirst(log.count - 300) }
    }

    private func quick(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(.bordered)
            .disabled(busy || package.isEmpty)
    }
}
#endif

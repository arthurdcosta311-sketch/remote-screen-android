#if ADB_MOBILE
import SwiftUI
import UniformTypeIdentifiers

/// Real adb from the iPhone: pair with the phone's wireless debugging, then run
/// `shell` commands as the phone's `shell` user.
struct AdbView: View {
    let phone: PairedPhone
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var adb = AdbHost.shared

    @State private var pairPort = ""
    @State private var code = ""
    @State private var connectPort = ""
    @State private var package = ""
    @State private var command = ""
    @State private var log: [String] = []
    @State private var busy = false
    @State private var showImport = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("On the phone: Developer options → Wireless debugging → **Pair device with pairing code**. Type the port and the six digits here.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    TextField("Pairing port", text: $pairPort)
                        .keyboardType(.numberPad)
                    TextField("Pairing code", text: $code)
                        .keyboardType(.numberPad)
                    Button("Pair") { pair() }
                        .disabled(busy || pairPort.isEmpty || code.isEmpty)
                } header: {
                    Text("1. Pair")
                }

                Section {
                    TextField("Connect port", text: $connectPort)
                        .keyboardType(.numberPad)
                    Text("The other port on the Wireless debugging screen, above the pairing one.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Connect") { connect() }
                        .disabled(busy || connectPort.isEmpty)
                    Button("Disconnect") { run(["disconnect"]) }
                } header: {
                    Text("2. Connect")
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
            .onAppear { busy = false }
        }
    }

    // MARK: Actions

    private func pair() {
        busy = true
        Task {
            let result = await adb.pair(
                host: phone.host,
                pairingPort: pairPort,
                code: code,
                connectPort: connectPort
            )
            await MainActor.run {
                record(result)
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

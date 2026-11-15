import SwiftUI
import UniformTypeIdentifiers

/// The receiver's side of the remote shell: a console, the phone's apps and
/// processes with their actions, and an APK push button.
struct ShellView: View {
    @StateObject private var model: ShellModel
    @Environment(\.dismiss) private var dismiss

    @State private var tab = 0
    @State private var text = ""
    @State private var filter = ""
    @State private var showImport = false
    @State private var confirmReboot = false

    init(phone: PairedPhone) {
        _model = StateObject(wrappedValue: ShellModel(phone: phone))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("View", selection: $tab) {
                    Text("Console").tag(0)
                    Text("Apps").tag(1)
                    Text("Processes").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.top, 8)

                switch tab {
                case 1: appsList
                case 2: processesList
                default: console
                }
            }
            .navigationTitle(model.phone.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showImport = true
                    } label: {
                        Label("Push APK", systemImage: "square.and.arrow.up")
                    }
                    .disabled(!model.ready)
                }
            }
            .fileImporter(
                isPresented: $showImport,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        tab = 0
                        model.pushApk(url: url)
                    }
                case .failure(let error):
                    model.append("! \(error.localizedDescription)")
                }
            }
            .alert("Restart the phone?", isPresented: $confirmReboot) {
                Button("Restart", role: .destructive) {
                    Task { await model.runCommand("reboot") }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Only works when the phone is a device owner or rooted.")
            }
            .onAppear { model.connect() }
            .onDisappear { model.disconnect() }
        }
    }

    // MARK: Console

    private var console: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Circle()
                    .fill(model.ready ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text(model.status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
                if let progress = model.progress {
                    ProgressView(value: progress).frame(width: 90)
                } else if model.busy {
                    ProgressView()
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(Array(model.lines.enumerated()), id: \.offset) { index, line in
                            Text(line)
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(colour(for: line))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(index)
                        }
                    }
                    .padding(8)
                }
                .background(Color.black.opacity(0.85))
                .onChange(of: model.lines.count) { newValue in
                    guard newValue > 0 else { return }
                    withAnimation { proxy.scrollTo(newValue - 1, anchor: .bottom) }
                }
            }

            quickCommands

            HStack(spacing: 8) {
                TextField("command", text: $text)
                    .font(.system(size: 14, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty || !model.ready)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
    }

    private func colour(for line: String) -> Color {
        if line.hasPrefix("!") { return .red }
        if line.hasPrefix("$") { return Color(red: 0.24, green: 0.86, blue: 0.59) }
        if line.hasPrefix("#") { return .secondary }
        return .primary
    }

    private var quickCommands: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("Rotate 90°") { run("rotate 90") }
                chip("Rotate 270°") { run("rotate 270") }
                chip("Auto-rotate") { run("rotate auto") }
                chip("Start mirroring") { run("mirror") }
                chip("Lock") { run("lock") }
                chip("Access") { run("access") }
                chip("Volume up") { run("volume up") }
                chip("Volume down") { run("volume down") }
                chip("Mute") { run("volume mute") }
                chip("Battery") { run("battery") }
                chip("Network") { run("network") }
                chip("Device info") { run("device") }
                chip("Apps") {
                    run("apps all")
                    tab = 1
                }
                chip("Processes") {
                    run("ps")
                    tab = 2
                }
                chip("Help") { run("help") }
                chip("Restart") { confirmReboot = true }
            }
            .padding(.horizontal, 8)
        }
        .padding(.vertical, 6)
        .background(Color(white: 0.08))
    }

    private func chip(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color(white: 0.18), in: Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .disabled(!model.ready)
    }

    private func send() {
        let command = text.trimmingCharacters(in: .whitespaces)
        guard !command.isEmpty else { return }
        text = ""
        run(command)
    }

    private func run(_ command: String) {
        Task { await model.runCommand(command) }
    }

    // MARK: Tables

    private var visibleApps: [AppRow] {
        guard !filter.isEmpty else { return model.apps }
        return model.apps.filter {
            $0.label.localizedCaseInsensitiveContains(filter) || $0.pkg.localizedCaseInsensitiveContains(filter)
        }
    }

    private var appsList: some View {
        VStack(spacing: 0) {
            toolbarRow(title: "\(model.apps.count) packages") { run("apps all") }
            List {
                ForEach(visibleApps) { app in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(app.label).font(.headline)
                            Spacer()
                            Text(app.isSystem ? "system" : "user")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Text(app.pkg).font(.caption).foregroundStyle(.secondary)
                        Text("v\(app.version) · uid \(app.uid) · \(app.state)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                    .swipeActions(edge: .leading) {
                        Button { run("start \(app.pkg)") } label: {
                            Label("Start", systemImage: "play.fill")
                        }
                        .tint(.green)
                        Button { run(app.state == "enabled" ? "disable \(app.pkg)" : "enable \(app.pkg)") } label: {
                            Label(app.state == "enabled" ? "Disable" : "Enable", systemImage: "power")
                        }
                        .tint(.orange)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { run("uninstall \(app.pkg)") } label: {
                            Label("Uninstall", systemImage: "trash")
                        }
                    }
                    .contextMenu {
                        Button("Inspect") { run("inspect \(app.pkg)") }
                        Button("Start") { run("start \(app.pkg)") }
                        Button("Kill background") { run("kill \(app.pkg)") }
                        Button("Force stop (root)") { run("stop \(app.pkg)") }
                        Button(app.state == "enabled" ? "Disable" : "Enable") {
                            run(app.state == "enabled" ? "disable \(app.pkg)" : "enable \(app.pkg)")
                        }
                        Button("Uninstall") { run("uninstall \(app.pkg)") }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if model.apps.isEmpty {
                    Text("Tap Refresh to load the phone's packages.").foregroundStyle(.secondary)
                }
            }
        }
    }

    private var processesList: some View {
        VStack(spacing: 0) {
            toolbarRow(title: "\(model.processes.count) processes") { run("ps") }
            List {
                ForEach(model.processes) { process in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(process.name).font(.callout)
                            Text("pid \(process.pid) · \(process.user)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button {
                            run("kill \(process.name.substringBeforeColon())")
                        } label: {
                            Image(systemName: "xmark.circle").foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if model.processes.isEmpty {
                    Text("Tap Refresh to list processes.").foregroundStyle(.secondary)
                }
            }
        }
    }

    private func toolbarRow(title: String, refresh: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            HStack {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Refresh", action: refresh).font(.caption)
            }
            .padding(.horizontal)
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).font(.caption)
                TextField("filter", text: $filter)
                    .font(.caption)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .padding(.horizontal)
        }
        .padding(.vertical, 6)
    }
}

private extension String {
    /// `com.example.app:remote` -> `com.example.app`
    func substringBeforeColon() -> String {
        String(split(separator: ":").first ?? Substring(self))
    }
}

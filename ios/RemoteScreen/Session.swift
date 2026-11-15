import Foundation
import CryptoKit
import UIKit

enum StreamMode: String, Identifiable {
    case mirror
    case virtual

    var id: String { rawValue }

    var port: UInt16 {
        switch self {
        case .mirror: return Proto.mirrorPort
        case .virtual: return Proto.virtualPort
        }
    }

    var title: String {
        switch self {
        case .mirror: return "Mirror"
        case .virtual: return "Second screen"
        }
    }
}

enum SessionClient {
    /// Port 7001/7002 handshake. Returns the open, encrypted channel.
    static func open(phone: PairedPhone, port: UInt16) async throws -> (TCPStream, SecureChannel) {
        let stream = TCPStream(host: phone.host, port: port)
        do {
            try await stream.connect()

            let magic = try await stream.readU32()
            guard magic == Proto.magic else { throw WireError.badMagic }
            let version = try await stream.readU32()
            guard version == Proto.version else { throw WireError.badVersion(version) }
            let serverNonce = try await stream.read(32)
            _ = try await stream.readUTF() // phone display name

            var clientNonce = Data(count: 32)
            for i in 0..<clientNonce.count { clientNonce[i] = UInt8.random(in: 0...255) }
            let proof = Crypto.hmac(phone.streamKey, serverNonce, clientNonce)

            var hello = ByteWriter()
            hello.bytes(phone.fingerprint)
            hello.bytes(clientNonce)
            hello.bytes(proof)
            try await stream.send(hello.data)

            // May wait up to a minute while the owner answers the notification.
            let status = try await stream.readU8()
            guard status == 1 else {
                let reason = (try? await stream.readUTF()) ?? "unknown reason"
                throw WireError.refused(reason)
            }

            let sessionKey = Crypto.hkdf(
                ikm: phone.streamKey,
                salt: serverNonce + clientNonce,
                info: "RemoteScreen v1 stream"
            )
            return (stream, SecureChannel(stream: stream, key: sessionKey))
        } catch {
            stream.close()
            throw error
        }
    }
}

@MainActor
final class SessionModel: ObservableObject {
    @Published var status = "Connecting…"
    @Published var isLive = false
    @Published var ended = false
    @Published var videoSize = CGSize.zero
    @Published var touchEnabled = false

    let renderer = VideoRenderer()
    let phone: PairedPhone
    let mode: StreamMode

    private var stream: TCPStream?
    private var channel: SecureChannel?
    private var task: Task<Void, Never>?
    private var lastMove = Date.distantPast

    init(phone: PairedPhone, mode: StreamMode) {
        self.phone = phone
        self.mode = mode
    }

    func start() {
        guard task == nil else { return }
        task = Task { await run() }
    }

    func stop() {
        task?.cancel()
        task = nil
        if let channel {
            sendControl(["t": "bye"])
            try? channel.send(type: Proto.Msg.bye)
        }
        channel = nil
        stream?.close()
        stream = nil
        renderer.reset()
    }

    private func run() async {
        do {
            status = "Waiting for the phone to allow this connection…"
            let (stream, channel) = try await SessionClient.open(phone: phone, port: mode.port)
            self.stream = stream
            self.channel = channel
            status = "Waiting for video…"

            if mode == .virtual {
                sendControl(["t": "name", "v": UIDevice.current.name])
            }

            while !Task.isCancelled {
                let message = try await channel.receive()
                switch message.type {
                case Proto.Msg.videoInfo:
                    handleInfo(message.payload)
                case Proto.Msg.videoFrame:
                    handleFrame(message.payload)
                case Proto.Msg.bye:
                    throw WireError.closed
                default:
                    break
                }
            }
        } catch {
            if !Task.isCancelled {
                if case .closed? = error as? WireError {
                    status = "Disconnected"
                } else {
                    status = error.localizedDescription
                }
                ended = true
                isLive = false
            }
        }
    }

    private func handleInfo(_ payload: Data) {
        guard let obj = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] else { return }
        let w = (obj["width"] as? NSNumber)?.doubleValue ?? 0
        let h = (obj["height"] as? NSNumber)?.doubleValue ?? 0
        if w > 0, h > 0 { videoSize = CGSize(width: w, height: h) }
        touchEnabled = (obj["touch"] as? Bool) ?? false
    }

    private func handleFrame(_ payload: Data) {
        guard payload.count > 9 else { return }
        let base = payload.startIndex
        var pts: UInt64 = 0
        for i in 1...8 { pts = (pts << 8) | UInt64(payload[base + i]) }
        renderer.submit(payload.subdata(in: (base + 9)..<payload.endIndex), ptsUs: Int64(bitPattern: pts))
        if !isLive {
            isLive = true
            status = "Live"
        }
    }

    // MARK: Input

    /// Maps a point in the (aspect-fit) video view to 0...1 video coordinates.
    func normalize(_ point: CGPoint, in size: CGSize) -> CGPoint? {
        guard videoSize.width > 0, videoSize.height > 0, size.width > 0, size.height > 0 else { return nil }
        let scale = min(size.width / videoSize.width, size.height / videoSize.height)
        let w = videoSize.width * scale
        let h = videoSize.height * scale
        let originX = (size.width - w) / 2
        let originY = (size.height - h) / 2
        let x = min(max((point.x - originX) / w, 0), 1)
        let y = min(max((point.y - originY) / h, 0), 1)
        return CGPoint(x: x, y: y)
    }

    func sendTouch(_ action: String, _ point: CGPoint?, force: Bool = false) {
        guard touchEnabled else { return }
        if action == "move" && !force {
            let now = Date()
            guard now.timeIntervalSince(lastMove) > 0.016 else { return }
            lastMove = now
        }
        var obj: [String: Any] = ["t": "touch", "a": action]
        if let point {
            obj["x"] = Double(point.x)
            obj["y"] = Double(point.y)
        }
        sendControl(obj)
    }

    func sendKey(_ key: String) {
        sendControl(["t": "key", "k": key])
    }

    func sendText(_ text: String) {
        sendControl(["t": "text", "v": text])
    }

    private func sendControl(_ object: [String: Any]) {
        guard let channel,
              let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        try? channel.send(type: Proto.Msg.control, payload: data)
    }
}

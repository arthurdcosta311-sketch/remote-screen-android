import Foundation
import CryptoKit
import Network

// MARK: - Constants

enum Proto {
    static let magic: UInt32 = 0x5253_5631 // "RSV1"
    static let version: UInt32 = 1

    static let pairPort: UInt16 = 7000
    static let mirrorPort: UInt16 = 7001
    static let virtualPort: UInt16 = 7002
    static let shellPort: UInt16 = 7003

    enum Msg {
        static let control: UInt8 = 0x10
        static let videoInfo: UInt8 = 0x11
        static let videoFrame: UInt8 = 0x12
        static let bye: UInt8 = 0x13
    }
}

enum WireError: LocalizedError {
    case closed
    case badMagic
    case badVersion(UInt32)
    case refused(String)
    case timeout(String?)
    case protocolError(String)

    var errorDescription: String? {
        switch self {
        case .closed: return "The connection was closed"
        case .badMagic: return "That is not a Remote Screen phone"
        case .badVersion(let v): return "Unsupported protocol version \(v)"
        case .refused(let why): return "The phone refused: \(why)"
        case .timeout(let detail):
            if let detail { return "Could not reach the phone (\(detail)). Check the address and Local Network permission." }
            return "Could not reach the phone. Check the address and Local Network permission."
        case .protocolError(let why): return "Protocol error: \(why)"
        }
    }
}

// MARK: - Crypto helpers (P-256 ECDH, HKDF-SHA256, HMAC-SHA256, AES-256-GCM)

enum Crypto {
    static func hkdf(ikm: Data, salt: Data, info: String, length: Int = 32) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: Data(info.utf8),
            outputByteCount: length
        )
        return key.withUnsafeBytes { Data($0) }
    }

    static func hmac(_ key: Data, _ parts: Data...) -> Data {
        var h = HMAC<SHA256>(key: SymmetricKey(data: key))
        for part in parts { h.update(data: part) }
        return Data(h.finalize())
    }

    static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    /// Six digit short authentication string; identical on phone and receiver.
    static func pairingCode(master: Data, devicePub: Data, receiverPub: Data) -> String {
        let mac = hmac(master, Data("code".utf8), devicePub, receiverPub)
        var value: UInt64 = 0
        for b in mac.prefix(4) { value = (value << 8) | UInt64(b) }
        let digits = String(value % 1_000_000)
        return String(repeating: "0", count: max(0, 6 - digits.count)) + digits
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Java DataOutputStream.writeUTF (modified UTF-8)

enum MUTF8 {
    static func encode(_ s: String) -> Data {
        var out = Data()
        for u in s.utf16 {
            if u >= 1 && u <= 0x7F {
                out.append(UInt8(u))
            } else if u <= 0x7FF {
                out.append(UInt8(0xC0 | (u >> 6)))
                out.append(UInt8(0x80 | (u & 0x3F)))
            } else {
                out.append(UInt8(0xE0 | (u >> 12)))
                out.append(UInt8(0x80 | ((u >> 6) & 0x3F)))
                out.append(UInt8(0x80 | (u & 0x3F)))
            }
        }
        return out
    }

    static func decode(_ d: Data) -> String {
        let b = [UInt8](d)
        var units = [UInt16]()
        var i = 0
        while i < b.count {
            let c = b[i]
            if c < 0x80 {
                units.append(UInt16(c))
                i += 1
            } else if c & 0xE0 == 0xC0, i + 1 < b.count {
                let hi = UInt16(c & 0x1F) << 6
                let lo = UInt16(b[i + 1] & 0x3F)
                units.append(hi | lo)
                i += 2
            } else if c & 0xF0 == 0xE0, i + 2 < b.count {
                let a = UInt16(c & 0x0F) << 12
                let m = UInt16(b[i + 1] & 0x3F) << 6
                let l = UInt16(b[i + 2] & 0x3F)
                units.append(a | m | l)
                i += 3
            } else {
                return String(decoding: d, as: UTF8.self)
            }
        }
        return String(decoding: units, as: UTF16.self)
    }
}

struct ByteWriter {
    var data = Data()

    mutating func u8(_ v: UInt8) { data.append(v) }

    mutating func u16(_ v: UInt16) {
        data.append(UInt8(v >> 8))
        data.append(UInt8(v & 0xFF))
    }

    mutating func u32(_ v: UInt32) {
        for shift in stride(from: 24, through: 0, by: -8) {
            data.append(UInt8((v >> UInt32(shift)) & 0xFF))
        }
    }

    mutating func bytes(_ d: Data) { data.append(d) }

    mutating func utf(_ s: String) {
        let e = MUTF8.encode(s)
        u16(UInt16(min(e.count, 0xFFFF)))
        data.append(e.prefix(0xFFFF))
    }
}

// MARK: - TCP

/// Minimal async wrapper around an NWConnection (raw TCP, no TLS).
final class TCPStream {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "remotescreen.tcp")

    init(host: String, port: UInt16) {
        let params = NWParameters.tcp
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            tcp.enableKeepalive = true
        }
        conn = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? 7000,
            using: params
        )
    }

    func connect(timeout: TimeInterval = 15) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var done = false
            var lastWait: String?
            conn.stateUpdateHandler = { state in
                guard !done else { return }
                switch state {
                case .ready:
                    done = true
                    cont.resume()
                case .failed(let error):
                    done = true
                    cont.resume(throwing: WireError.timeout(error.localizedDescription))
                case .waiting(let error):
                    // The Local Network permission prompt shows up as "waiting"; keep
                    // waiting until the timeout instead of failing right away.
                    lastWait = error.localizedDescription
                case .cancelled:
                    done = true
                    cont.resume(throwing: WireError.closed)
                default:
                    break
                }
            }
            queue.asyncAfter(deadline: .now() + timeout) { [conn] in
                if !done {
                    done = true
                    cont.resume(throwing: WireError.timeout(lastWait))
                    conn.cancel()
                }
            }
            conn.start(queue: queue)
        }
    }

    func read(_ n: Int) async throws -> Data {
        if n <= 0 { return Data() }
        var out = Data()
        out.reserveCapacity(n)
        while out.count < n {
            let want = n - out.count
            let chunk: Data = try await withCheckedThrowingContinuation { cont in
                conn.receive(minimumIncompleteLength: 1, maximumLength: want) { data, _, _, error in
                    if let error {
                        cont.resume(throwing: error)
                    } else if let data, !data.isEmpty {
                        cont.resume(returning: data)
                    } else {
                        cont.resume(throwing: WireError.closed)
                    }
                }
            }
            out.append(chunk)
        }
        return out
    }

    func readU8() async throws -> UInt8 {
        try await read(1)[0]
    }

    func readU16() async throws -> UInt16 {
        let d = try await read(2)
        return (UInt16(d[0]) << 8) | UInt16(d[1])
    }

    func readU32() async throws -> UInt32 {
        let d = try await read(4)
        var v: UInt32 = 0
        for b in d { v = (v << 8) | UInt32(b) }
        return v
    }

    func readUTF() async throws -> String {
        let n = Int(try await readU16())
        return MUTF8.decode(try await read(n))
    }

    /// Fire and forget; ordering of calls is preserved by the connection.
    func write(_ data: Data) {
        conn.send(content: data, completion: .contentProcessed { _ in })
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    func close() {
        conn.cancel()
    }
}

// MARK: - Encrypted, framed channel

/// u32 length || AES-256-GCM(type || payload). Nonce = 4 zero bytes || u64 BE counter,
/// one counter per direction, no AAD.
final class SecureChannel {
    private let stream: TCPStream
    private let key: SymmetricKey
    private let lock = NSLock()
    private var sendSeq: UInt64 = 0
    private var recvSeq: UInt64 = 0

    init(stream: TCPStream, key: Data) {
        self.stream = stream
        self.key = SymmetricKey(data: key)
    }

    private func nonce(_ seq: UInt64) throws -> AES.GCM.Nonce {
        var d = Data(repeating: 0, count: 4)
        for shift in stride(from: 56, through: 0, by: -8) {
            d.append(UInt8((seq >> UInt64(shift)) & 0xFF))
        }
        return try AES.GCM.Nonce(data: d)
    }

    func send(type: UInt8, payload: Data = Data()) throws {
        lock.lock()
        defer { lock.unlock() }
        var plain = Data([type])
        plain.append(payload)
        let box = try AES.GCM.seal(plain, using: key, nonce: nonce(sendSeq))
        sendSeq += 1
        var w = ByteWriter()
        w.u32(UInt32(box.ciphertext.count + box.tag.count))
        w.bytes(box.ciphertext)
        w.bytes(box.tag)
        stream.write(w.data)
    }

    func receive() async throws -> (type: UInt8, payload: Data) {
        let length = Int(try await stream.readU32())
        guard length >= 17, length <= 8 * 1024 * 1024 else {
            throw WireError.protocolError("bad frame length \(length)")
        }
        let sealed = try await stream.read(length)
        let box = try AES.GCM.SealedBox(
            nonce: nonce(recvSeq),
            ciphertext: sealed.prefix(length - 16),
            tag: sealed.suffix(16)
        )
        let plain = try AES.GCM.open(box, using: key)
        recvSeq += 1
        guard let type = plain.first else { throw WireError.protocolError("empty frame") }
        return (type, Data(plain.dropFirst()))
    }
}

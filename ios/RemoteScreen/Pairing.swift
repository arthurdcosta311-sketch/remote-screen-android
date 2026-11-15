import Foundation
import Combine
import CryptoKit
import Security

/// A phone this receiver has paired with. `streamKey` is the long-term secret from pairing;
/// traffic is never encrypted with it directly.
struct PairedPhone: Codable, Identifiable, Equatable {
    var id: String { Crypto.hex(fingerprint) }
    var name: String
    var host: String
    /// SHA-256 of our SPKI public key, which is how the phone finds us.
    var fingerprint: Data
    var streamKey: Data
    var pairedAt: Date
}

enum PairingClient {
    /// Runs the port 7000 handshake. `onCode` is called once the six digit code is known, right
    /// before the receiver proves it; the phone shows the same code in its "Pair with …?" dialog.
    static func pair(
        host: String,
        receiverName: String,
        onCode: @escaping @MainActor (_ code: String, _ phoneName: String) -> Void
    ) async throws -> PairedPhone {
        let stream = TCPStream(host: host, port: Proto.pairPort)
        defer { stream.close() }
        try await stream.connect()

        let magic = try await stream.readU32()
        guard magic == Proto.magic else { throw WireError.badMagic }
        let version = try await stream.readU32()
        guard version == Proto.version else { throw WireError.badVersion(version) }
        let devicePubLen = Int(try await stream.readU16())
        let devicePub = try await stream.read(devicePubLen)
        let saltLen = Int(try await stream.readU16())
        let salt = try await stream.read(saltLen)
        let phoneName = try await stream.readUTF()

        let privateKey = P256.KeyAgreement.PrivateKey()
        let receiverPub = privateKey.publicKey.derRepresentation
        let phoneKey = try P256.KeyAgreement.PublicKey(derRepresentation: devicePub)

        var reply = ByteWriter()
        reply.u16(UInt16(receiverPub.count))
        reply.bytes(receiverPub)
        reply.utf(receiverName)
        try await stream.send(reply.data)

        let secret = try privateKey.sharedSecretFromKeyAgreement(with: phoneKey)
        let shared = secret.withUnsafeBytes { Data($0) }
        let master = Crypto.hkdf(ikm: shared, salt: salt, info: "RemoteScreen v1 pairing")
        let code = Crypto.pairingCode(master: master, devicePub: devicePub, receiverPub: receiverPub)

        await onCode(code, phoneName)

        let typed = Data(code.utf8)
        let proof = Crypto.hmac(master, Data("confirm".utf8), typed)
        var confirm = ByteWriter()
        confirm.u16(UInt16(typed.count))
        confirm.bytes(typed)
        confirm.u16(UInt16(proof.count))
        confirm.bytes(proof)
        try await stream.send(confirm.data)

        // Waits for the owner to tap "Pair" on the phone (up to two minutes).
        let status = try await stream.readU8()
        guard status == 1 else {
            let reason = (try? await stream.readUTF()) ?? "unknown reason"
            throw WireError.refused(reason)
        }
        let finalName = (try? await stream.readUTF()) ?? phoneName

        let streamKey = Crypto.hkdf(ikm: shared, salt: salt, info: "RemoteScreen v1 session")
        return PairedPhone(
            name: finalName.isEmpty ? phoneName : finalName,
            host: host,
            fingerprint: Crypto.sha256(receiverPub),
            streamKey: streamKey,
            pairedAt: Date()
        )
    }
}

/// Everything that stays on the iPhone, kept in the Keychain.
enum Keychain {
    private static let service = "dev.remotescreen.receiver"

    static func load(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    static func save(_ data: Data, account: String) {
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let update: [String: Any] = [kSecValueData as String: data]
        if SecItemUpdate(match as CFDictionary, update as CFDictionary) == errSecItemNotFound {
            var add = match
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}

@MainActor
final class PhoneStore: ObservableObject {
    @Published private(set) var phones: [PairedPhone] = []
    private let account = "paired-phones"

    init() {
        if let data = Keychain.load(account),
           let list = try? JSONDecoder().decode([PairedPhone].self, from: data) {
            phones = list
        }
    }

    func add(_ phone: PairedPhone) {
        phones.removeAll { $0.id == phone.id }
        phones.append(phone)
        persist()
    }

    func remove(_ phone: PairedPhone) {
        phones.removeAll { $0.id == phone.id }
        persist()
    }

    func updateHost(_ phone: PairedPhone, host: String) {
        guard let i = phones.firstIndex(where: { $0.id == phone.id }) else { return }
        phones[i].host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(phones) {
            Keychain.save(data, account: account)
        }
    }
}

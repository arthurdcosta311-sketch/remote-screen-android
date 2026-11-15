import Foundation
import AVFoundation
import VideoToolbox
import UIKit

/// Annex-B H.264 -> VTDecompressionSession -> AVSampleBufferDisplayLayer.
/// `submit` can be called from any thread; decoding runs on a private serial queue.
final class VideoRenderer {
    let layer = AVSampleBufferDisplayLayer()
    private let queue = DispatchQueue(label: "remotescreen.decode", qos: .userInteractive)
    private let decoder = H264Decoder()

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = UIColor.black.cgColor
        decoder.onFrame = { [weak self] pixelBuffer, pts in
            self?.display(pixelBuffer, pts: pts)
        }
    }

    func submit(_ annexB: Data, ptsUs: Int64) {
        queue.async { [decoder] in
            decoder.decode(annexB, ptsUs: ptsUs)
        }
    }

    func reset() {
        queue.async { [decoder, layer] in
            decoder.invalidate()
            layer.flushAndRemoveImage()
        }
    }

    private func display(_ pixelBuffer: CVPixelBuffer, pts: CMTime) {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &format
        ) == noErr, let format else { return }

        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: format,
            sampleTiming: &timing,
            sampleBufferOut: &sample
        ) == noErr, let sample else { return }

        // Live stream: show every frame as soon as it is decoded.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }

        if layer.status == .failed { layer.flush() }
        layer.enqueue(sample)
    }
}

/// Not thread safe: use from one queue.
final class H264Decoder {
    var onFrame: ((CVPixelBuffer, CMTime) -> Void)?

    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var sps: [UInt8]?
    private var pps: [UInt8]?

    func invalidate() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        format = nil
        sps = nil
        pps = nil
    }

    func decode(_ data: Data, ptsUs: Int64) {
        let bytes = [UInt8](data)
        var newSPS: [UInt8]?
        var newPPS: [UInt8]?
        var avcc = [UInt8]()
        avcc.reserveCapacity(bytes.count + 16)

        for range in H264Decoder.splitAnnexB(bytes) where !range.isEmpty {
            let nalType = bytes[range.lowerBound] & 0x1F
            switch nalType {
            case 7:
                newSPS = Array(bytes[range])
            case 8:
                newPPS = Array(bytes[range])
            case 1...5:
                let n = UInt32(range.count)
                avcc.append(UInt8((n >> 24) & 0xFF))
                avcc.append(UInt8((n >> 16) & 0xFF))
                avcc.append(UInt8((n >> 8) & 0xFF))
                avcc.append(UInt8(n & 0xFF))
                avcc.append(contentsOf: bytes[range])
            default:
                break // AUD, SEI, filler
            }
        }

        if let s = newSPS, let p = newPPS, s != sps || p != pps || format == nil {
            configure(sps: s, pps: p)
        }
        guard !avcc.isEmpty, let format else { return }
        guard let session = ensureSession(for: format) else { return }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &block
        ) == kCMBlockBufferNoErr, let block else { return }
        guard CMBlockBufferReplaceDataBytes(
            with: avcc,
            blockBuffer: block,
            offsetIntoDestination: 0,
            dataLength: avcc.count
        ) == kCMBlockBufferNoErr else { return }

        let pts = CMTime(value: ptsUs, timescale: 1_000_000)
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var size = avcc.count
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: block,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &size,
            sampleBufferOut: &sample
        ) == noErr, let sample else { return }

        var infoFlags = VTDecodeInfoFlags()
        let status = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sample,
            flags: [],
            infoFlagsOut: &infoFlags
        ) { [weak self] status, _, imageBuffer, presentationTime, _ in
            guard status == noErr, let imageBuffer else { return }
            self?.onFrame?(imageBuffer, presentationTime)
        }

        if status == kVTInvalidSessionErr || status == kVTVideoDecoderMalfunctionErr {
            // Typically after the app was backgrounded; rebuilt on the next IDR/slice.
            VTDecompressionSessionInvalidate(session)
            self.session = nil
        }
    }

    private func configure(sps s: [UInt8], pps p: [UInt8]) {
        var newFormat: CMVideoFormatDescription?
        let status: OSStatus = s.withUnsafeBufferPointer { sp in
            p.withUnsafeBufferPointer { pp in
                let pointers: [UnsafePointer<UInt8>] = [sp.baseAddress!, pp.baseAddress!]
                let sizes: [Int] = [sp.count, pp.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: pointers,
                    parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &newFormat
                )
            }
        }
        guard status == noErr, let newFormat else { return }

        if let session, !VTDecompressionSessionCanAcceptFormatDescription(session, formatDescription: newFormat) {
            VTDecompressionSessionInvalidate(session)
            self.session = nil
        }
        format = newFormat
        sps = s
        pps = p
    }

    private func ensureSession(for format: CMVideoFormatDescription) -> VTDecompressionSession? {
        if let session { return session }
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
        ]
        var created: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: format,
            decoderSpecification: nil,
            imageBufferAttributes: attributes as CFDictionary,
            outputCallback: nil,
            decompressionSessionOut: &created
        )
        guard status == noErr, let created else { return nil }
        VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        session = created
        return created
    }

    /// Ranges of NAL units (start codes excluded) in an Annex-B buffer.
    static func splitAnnexB(_ b: [UInt8]) -> [Range<Int>] {
        var starts = [Int]()
        var codeBegins = [Int]()
        var i = 0
        let n = b.count
        while i + 2 < n {
            if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
                codeBegins.append((i > 0 && b[i - 1] == 0) ? i - 1 : i)
                starts.append(i + 3)
                i += 3
            } else {
                i += 1
            }
        }
        var ranges = [Range<Int>]()
        for k in 0..<starts.count {
            let end = k + 1 < starts.count ? codeBegins[k + 1] : n
            if end > starts[k] { ranges.append(starts[k]..<end) }
        }
        return ranges
    }
}

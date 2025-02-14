import core
import Foundation
import Shared

public final class TeltraImpl: Shared.Teltra {

    public init() {}

    public func translateBitlogs(
        bitlogs: [KotlinUByte],
        identifiers: Shared.TelemetryIdentifiers
    ) -> [[KotlinUByte]] {
        do {
            return try core.Teltra().translateBitlogs(
                bitlogBytes: bitlogs.map { $0.uint8Value },
                identifiers: core.TelemetryIdentifiers(
                    serial: identifiers.serial,
                    version: identifiers.version,
                    swType: identifiers.swType,
                    hwRevision: identifiers.hwRevision
                )
            ).map { $0.map { KotlinUByte(unsignedChar: $0) } }
        } catch {
            return []
        }
    }

}

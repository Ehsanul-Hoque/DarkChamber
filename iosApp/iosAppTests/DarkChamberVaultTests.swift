import XCTest
import Shared

/// Cycle 5a iOS acceptance (D3): drives the exported `Shared` framework on the simulator/device —
/// the same cold-open + encrypted round-trip the Kotlin `iosSimulatorArm64Test` covers, but through
/// the Swift ↔ framework boundary the app itself uses.
///
/// Add this file to a Unit Testing Bundle target hosted by the `iosApp` app (see
/// docs/verification/app-bootstrap-manual.md), then run with ⌘U or
/// `xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17'`.
final class DarkChamberVaultTests: XCTestCase {

    /// Cold-open the real vault and round-trip one encrypted object end to end.
    func testColdOpenSelfCheckSucceeds() {
        XCTAssertTrue(
            IosBootstrapKt.runVaultSelfCheck(),
            "iOS cold-open + encrypted round-trip failed"
        )
    }

    /// The Documents vault root must be excluded from iCloud/iTunes backup (deferred cycle-3 call).
    func testVaultRootIsExcludedFromBackup() throws {
        // Ensure the vault root exists (runVaultSelfCheck resolves + creates <Documents>/vault).
        XCTAssertTrue(IosBootstrapKt.runVaultSelfCheck())

        let documents = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let vaultRoot = documents.appendingPathComponent("vault", isDirectory: true)
        let values = try vaultRoot.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(
            values.isExcludedFromBackup,
            true,
            "vault root is not isExcludedFromBackup"
        )
    }
}

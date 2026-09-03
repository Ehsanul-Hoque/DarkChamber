import XCTest
import Shared

/// iOS acceptance: drives the exported `Shared` framework on the simulator — building
/// the shared Compose session gate (`MainViewController()`) through the Swift ↔ framework boundary the
/// app itself uses. Building the gate resolves `<Documents>/vault` (the locked screen probes the
/// keystore), so the backup-exclusion attribute can be checked afterwards.
///
/// The full create → unlock flow is covered by the Kotlin `iosSimulatorArm64Test`
/// (`IosVaultRootResolverTest`); real biometrics live on the on-device checklist
/// (`docs/verification/session-gate-manual.md`). Run with ⌘U or
/// `xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17'`.
final class DarkChamberVaultTests: XCTestCase {

    /// The shared Compose gate builds through the framework (Koin graph + Decompose root construct).
    @MainActor
    func testMainViewControllerBuildsThroughTheFramework() {
        let viewController = MainViewControllerKt.MainViewController()
        XCTAssertNotNil(viewController, "the shared Compose session gate failed to build")
    }

    /// The Documents vault root must be excluded from iCloud/iTunes backup (runtime call).
    @MainActor
    func testVaultRootIsExcludedFromBackup() throws {
        // Building the gate resolves + creates <Documents>/vault and sets the backup-exclusion flag.
        _ = MainViewControllerKt.MainViewController()

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

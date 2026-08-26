import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Cycle 5a bring-up probe: cold-open the real vault (Documents root, Keychain keystore,
        // native SQLite catalog) and round-trip one encrypted object on device/simulator. No vault
        // UI yet — this only proves the shared engine composes through the Shared framework.
        let ok = IosBootstrapKt.runVaultSelfCheck()
        print("DarkChamber vault self-check: \(ok ? "OK" : "FAILED")")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

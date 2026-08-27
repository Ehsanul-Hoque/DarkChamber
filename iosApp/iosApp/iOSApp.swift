import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Cycle 5a bring-up probe: cold-open the real vault (Documents root, Keychain keystore,
        // native SQLite catalog) and round-trip one encrypted object on device/simulator. No vault
        // UI yet — this only proves the shared engine composes through the Shared framework.
        //
        // DEBUG-only: the probe persists keys/mk.wrapped under a hardcoded diagnostic passphrase, so
        // it must never run in a release build (it would shadow the user's real vault creation). 5b
        // replaces this with the real create/unlock UX and removes the probe entirely.
        #if DEBUG
        let ok = IosBootstrapKt.runVaultSelfCheck()
        print("DarkChamber vault self-check: \(ok ? "OK" : "FAILED")")
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

import SwiftUI
import Shared

/// Hosts the shared Compose session gate (Kotlin `MainViewController()` from the `Shared`
/// framework) inside SwiftUI. The gate owns the lock/create/unlock UI; SwiftUI only provides the
/// window. Mirrors the Android `MainActivity` host (cycle 5b).
struct ComposeGateView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeGateView()
            .ignoresSafeArea()
    }
}

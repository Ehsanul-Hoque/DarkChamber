import SwiftUI
import Shared

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// Where UIKit asks which orientations the window may take. The camera screen holds portrait while
/// it is in front (Kotlin `holdsPortrait()`); otherwise the answer is the one the project's
/// Info.plist settings give.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        if OrientationKt.holdsPortrait() { return .portrait }
        return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
}

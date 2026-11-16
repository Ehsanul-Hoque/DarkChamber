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

/// Where UIKit asks which orientations the window may take. A screen holding portrait (the camera, or
/// the player's portrait choice) gets portrait (Kotlin `holdsPortrait()`); the player's landscape
/// choice gets either landscape (`holdsLandscape()`); otherwise the answer is the one the project's
/// Info.plist settings give.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        if OrientationKt.holdsPortrait() { return .portrait }
        if OrientationKt.holdsLandscape() { return .landscape }
        return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
}

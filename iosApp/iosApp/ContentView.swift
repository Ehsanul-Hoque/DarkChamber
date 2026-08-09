import SwiftUI

// Phase 0 walking-skeleton stub. The Kotlin/Compose host (via a Shared framework) was
// removed when :shared was retired; it returns in the core:crypto cycle when there is
// shared iOS code to export (see .claude/phases/phase-0/cycle-1/spec.md §6). Mirrors the
// Android inline skeleton (androidApp MainActivity.WalkingSkeleton).
struct ContentView: View {
    var body: some View {
        Text("DarkChamber")
    }
}

#Preview {
    ContentView()
}

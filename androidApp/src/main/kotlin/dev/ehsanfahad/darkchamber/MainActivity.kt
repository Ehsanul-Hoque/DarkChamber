package dev.ehsanfahad.darkchamber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { WalkingSkeleton() }
    }
}

/**
 * Phase 0 walking skeleton: proves the Compose app boots and renders. Replaced by the
 * Decompose root (LockedComponent / UnlockedComponent — see docs/ARCHITECTURE.md §session
 * gate) once feature UI lands. Deliberately trivial — no vault content exists yet.
 */
@Composable
private fun WalkingSkeleton() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("DarkChamber")
            }
        }
    }
}

@Preview
@Composable
private fun WalkingSkeletonPreview() {
    WalkingSkeleton()
}

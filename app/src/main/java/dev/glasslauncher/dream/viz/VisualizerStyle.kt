package dev.glasslauncher.dream.viz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The visualizer looks (P334), chosen in Settings > Screen Saver. The serial names keep configs saved under the first five working. */
@Serializable
enum class VisualizerStyle(val label: String) {
    @SerialName("Bars") Spectrum("Spectrum"),
    @SerialName("BarsWaves") Tunnel("Tunnel"),
    @SerialName("Feedback") Warp("Warp"),
    Ribbons("Ribbons"),
    Grid("Grid"),
    Scope("Scope"),
}

package dev.glasslauncher.dream.viz

import kotlinx.serialization.Serializable

/** The visualizer looks (P334), chosen in Settings > Screen Saver. */
@Serializable
enum class VisualizerStyle(val label: String) {
    Bars("Bars"),
    Scope("Scope"),
    BarsWaves("Bars and Waves"),
    Feedback("Feedback"),
    Ribbons("Ribbons"),
}

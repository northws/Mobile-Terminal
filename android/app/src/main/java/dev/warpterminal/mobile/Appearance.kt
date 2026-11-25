package dev.warpterminal.mobile

internal enum class Palette(val label: String) {
    Mint("默认薄荷"), Ocean("深色海蓝"), Paper("浅色纸白"),
}

internal enum class TerminalSize(val label: String, val blockSp: Int, val gridSp: Int) {
    Compact("紧凑", 11, 12), Standard("标准", 13, 14), Large("大字", 16, 17),
}

internal data class Appearance(
    val palette: Palette = Palette.Mint,
    val size: TerminalSize = TerminalSize.Standard,
) {
    companion object {
        fun restore(palette: String?, size: String?) = Appearance(
            Palette.entries.find { it.name == palette } ?: Palette.Mint,
            TerminalSize.entries.find { it.name == size } ?: TerminalSize.Standard,
        )
    }
}

// Reconcile the saved owner with the live selection before touching the window.
internal fun fullscreenOwner(owner: Int?, selected: Int?): Int? = owner?.takeIf { it == selected }

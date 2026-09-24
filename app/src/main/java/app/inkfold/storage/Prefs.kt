package app.inkfold.storage

import android.content.Context
import android.graphics.Color
import app.inkfold.drawing.EraserMode
import app.inkfold.drawing.ToolSettings
import app.inkfold.model.ToolType

/** Persists the editor's tool selection and pen/touch mode across sessions. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("editor", Context.MODE_PRIVATE)

    /** Pen mode: true = stylus draws / finger scrolls. */
    var penMode: Boolean
        get() = sp.getBoolean(KEY_PEN_MODE, false)
        set(value) { sp.edit().putBoolean(KEY_PEN_MODE, value).apply() }

    fun loadSettings(): ToolSettings = ToolSettings(
        tool = enumOr(sp.getString(KEY_TOOL, null), ToolType.PEN),
        penColor = sp.getInt(KEY_PEN_COLOR, Color.BLACK),
        penSize = sp.getFloat(KEY_PEN_SIZE, 4f),
        highlighterColor = sp.getInt(KEY_HL_COLOR, Color.YELLOW),
        highlighterSize = sp.getFloat(KEY_HL_SIZE, 28f),
        eraserSize = sp.getFloat(KEY_ERASER_SIZE, 40f),
        eraserMode = enumOr(sp.getString(KEY_ERASER_MODE, null), EraserMode.OBJECT),
    )

    fun saveSettings(s: ToolSettings) {
        sp.edit()
            .putString(KEY_TOOL, s.tool.name)
            .putInt(KEY_PEN_COLOR, s.penColor)
            .putFloat(KEY_PEN_SIZE, s.penSize)
            .putInt(KEY_HL_COLOR, s.highlighterColor)
            .putFloat(KEY_HL_SIZE, s.highlighterSize)
            .putFloat(KEY_ERASER_SIZE, s.eraserSize)
            .putString(KEY_ERASER_MODE, s.eraserMode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_PEN_MODE = "penMode"
        const val KEY_TOOL = "tool"
        const val KEY_PEN_COLOR = "penColor"
        const val KEY_PEN_SIZE = "penSize"
        const val KEY_HL_COLOR = "hlColor"
        const val KEY_HL_SIZE = "hlSize"
        const val KEY_ERASER_SIZE = "eraserSize"
        const val KEY_ERASER_MODE = "eraserMode"
    }
}

package org.ivanivorontsov.tiho.browser

/** Dialog model rendered by the Compose UI (one at a time, queued). */
sealed class UiDialog {
    abstract val title: String
    data class Message(
        override val title: String, val text: String, val ok: String = "OK", val cancel: String? = null,
        val danger: Boolean = false, val onResult: (Boolean) -> Unit = {},
    ) : UiDialog()
    data class Field(val label: String, val initial: String = "", val password: Boolean = false)
    data class Input(override val title: String, val text: String, val fields: List<Field>, val onResult: (List<String>?) -> Unit) : UiDialog()
    data class Choice(
        override val title: String, val options: List<Triple<String, String, Boolean>>, val multiple: Boolean,
        val selected: Set<String>, val onResult: (List<String>?) -> Unit,
    ) : UiDialog()
    data class Menu(override val title: String, val items: List<Pair<String, () -> Unit>>, val onDismiss: () -> Unit = {}) : UiDialog()
}

data class Snack(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

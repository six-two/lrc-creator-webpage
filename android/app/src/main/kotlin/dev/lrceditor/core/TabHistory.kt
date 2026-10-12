package dev.lrceditor.core

/** Which screens were used before the current one, for the Back button: most recent last, each screen at most once. */
class TabHistory<T> {
    private val stack = ArrayDeque<T>()

    val canGoBack get() = stack.isNotEmpty()

    /** Records that the user moves from [from] to [to]. */
    fun moved(from: T, to: T) {
        if (from == to) return
        stack.remove(to)
        stack.remove(from)
        stack.addLast(from)
    }

    /** The screen Back goes to, removed from the history; null if there is none. */
    fun back(): T? = stack.removeLastOrNull()
}

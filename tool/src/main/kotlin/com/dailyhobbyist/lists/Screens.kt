package com.dailyhobbyist.lists

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val SORT_LETTERS = listOf("A", "B", "C")

/**
 * Build a breadcrumb that favors the current location. Shows the last
 * couple of segments (the current list and its parent), trimming the
 * front to "…/" so where you *are* is always what's visible.
 */
private fun buildBreadcrumb(names: List<String>, maxChars: Int = 24): String {
    if (names.isEmpty()) return ""
    val upper = names.map { it.uppercase() }
    val full = upper.joinToString("/")
    if (full.length <= maxChars) return full
    // Keep segments from the end until we'd exceed the width.
    val kept = ArrayDeque<String>()
    for (name in upper.asReversed()) {
        val candidate = (listOf(name) + kept).joinToString("/")
        if (("…/$candidate").length > maxChars && kept.isNotEmpty()) break
        kept.addFirst(name)
    }
    return "…/" + kept.joinToString("/")
}

/** What the screen is currently showing. */
sealed class Mode {
    object Browse : Mode()

    /** Full-screen text entry. [targetId] == null means "add new item". */
    data class Input(
        val session: Long,
        val targetId: String?,
        val title: String,
        val initial: String,
    ) : Mode()

    /** Options for one item (rename / move). */
    data class Options(val itemId: String) : Mode()

    /** Choosing a destination to move [itemId] into. [browseId] = list being shown. */
    data class MoveTarget(val itemId: String, val browseId: String) : Mode()

    /** Per-list settings behind the gear. */
    data class Settings(
        val confirmPurge: Boolean = false,
        val confirmPurgeAll: Boolean = false,
    ) : Mode()

    /** Sort phase 1: assign A / B / C letters. */
    object SortLetters : Mode()

    /** Sort phase 2: assign numbers within one letter group. */
    data class SortNumbers(val groupIndex: Int) : Mode()

    /** Backup / restore screen. */
    data class Backup(
        val message: String? = null,
        val confirmImport: Boolean = false,
    ) : Mode()
}

class NodeViewModel(val nodeId: String) : LightViewModel<Unit>() {

    val mode = MutableStateFlow<Mode>(Mode.Browse)
    val editMode = MutableStateFlow(false)

    /** Working sort labels while the sort flow is open. itemId -> label. */
    val draft = MutableStateFlow<Map<String, DraftLabel>>(emptyMap())

    fun startAdd() {
        mode.value = Mode.Input(
            session = System.nanoTime(),
            targetId = null,
            title = "ADD ITEM",
            initial = "",
        )
    }

    fun startRename(item: Node) {
        mode.value = Mode.Input(
            session = System.nanoTime(),
            targetId = item.id,
            title = "RENAME",
            initial = item.name,
        )
    }

    fun submitInput(text: CharSequence) {
        val m = mode.value
        if (m is Mode.Input) {
            if (m.targetId == null) {
                ListsRepo.addChild(nodeId, text.toString())
                mode.value = Mode.Browse
            } else {
                ListsRepo.rename(m.targetId, text.toString())
                mode.value = Mode.Options(m.targetId)
            }
        } else {
            mode.value = Mode.Browse
        }
    }

    fun cancelInput() {
        val m = mode.value
        mode.value = if (m is Mode.Input && m.targetId != null) {
            Mode.Options(m.targetId)
        } else {
            Mode.Browse
        }
    }

    fun openOptions(itemId: String) {
        mode.value = Mode.Options(itemId)
    }

    fun copyItem(itemId: String) {
        ListsRepo.copyNode(itemId)
        mode.value = Mode.Options(itemId)
    }

    fun pasteHere() {
        ListsRepo.pasteNode(nodeId)
        mode.value = Mode.Browse
    }

    /* ---- Move an item into a different list ---- */

    fun startMove(itemId: String) {
        // Begin browsing for a destination from the top (root).
        mode.value = Mode.MoveTarget(itemId, ROOT_ID)
    }

    fun moveBrowseInto(childId: String) {
        val m = mode.value as? Mode.MoveTarget ?: return
        mode.value = m.copy(browseId = childId)
    }

    fun moveBrowseUp() {
        val m = mode.value as? Mode.MoveTarget ?: return
        if (m.browseId == ROOT_ID) {
            // Back out to the item's own options.
            mode.value = Mode.Options(m.itemId)
        } else {
            val parent = ListsRepo.root.value.parentOf(m.browseId) ?: ROOT_ID
            mode.value = m.copy(browseId = parent)
        }
    }

    fun confirmMove() {
        val m = mode.value as? Mode.MoveTarget ?: return
        ListsRepo.moveNode(m.itemId, m.browseId)
        mode.value = Mode.Browse
    }

    fun openSettings() {
        mode.value = Mode.Settings()
    }

    fun backToBrowse() {
        mode.value = Mode.Browse
    }

    fun toggleEdit() {
        editMode.value = !editMode.value
        if (!editMode.value) mode.value = Mode.Browse
    }

    fun moveItem(itemId: String, up: Boolean) {
        val parent = ListsRepo.node(nodeId) ?: return
        val from = parent.children.indexOfFirst { it.id == itemId }
        if (from < 0) return
        val to = if (up) from - 1 else from + 1
        if (to !in parent.children.indices) return
        ListsRepo.move(nodeId, from, to)
    }

    fun deleteItem(itemId: String) {
        ListsRepo.delete(itemId)
    }

    fun toggleChecked(itemId: String) {
        ListsRepo.toggleChecked(itemId)
    }

    fun toggleChecklist() {
        val node = ListsRepo.node(nodeId) ?: return
        ListsRepo.setChecklist(nodeId, !node.checklist)
    }

    fun toggleChecklistAll() {
        val node = ListsRepo.node(nodeId) ?: return
        ListsRepo.setChecklistAll(nodeId, !node.isAllChecklist())
    }

    fun purgeTapped() {
        val m = mode.value
        if (m is Mode.Settings && m.confirmPurge) {
            ListsRepo.purgeChecked(nodeId)
            mode.value = Mode.Settings()
        } else {
            mode.value = Mode.Settings(confirmPurge = true)
        }
    }

    fun purgeAllTapped() {
        val m = mode.value
        if (m is Mode.Settings && m.confirmPurgeAll) {
            ListsRepo.purgeCheckedAll(nodeId)
            mode.value = Mode.Settings()
        } else {
            mode.value = Mode.Settings(confirmPurgeAll = true)
        }
    }

    /* ---------- Sort flow ---------- */

    fun startSort() {
        val node = ListsRepo.node(nodeId) ?: return
        if (node.children.isEmpty()) return
        // Pre-fill the draft with any labels the list already has,
        // so re-sorting is a tweak instead of a redo.
        draft.value = node.children.associate {
            it.id to DraftLabel(it.sortLetter, it.sortNumber)
        }
        mode.value = Mode.SortLetters
    }

    fun cancelSort() {
        draft.value = emptyMap()
        mode.value = Mode.Browse
    }

    /** Letters that currently have at least one item assigned, in A-B-C order. */
    fun sortGroups(): List<String> =
        SORT_LETTERS.filter { g -> draft.value.values.any { it.letter == g } }

    /** Tap a letter chip: assign, switch, or clear. */
    fun setDraftLetter(itemId: String, letter: String) {
        val current = draft.value.toMutableMap()
        val old = current[itemId] ?: DraftLabel()
        val oldLetter = old.letter
        if (oldLetter == letter) {
            // Tapping the same letter clears it.
            current[itemId] = DraftLabel(null, null)
        } else {
            // New letter — number resets, it belongs to the old group's order.
            current[itemId] = DraftLabel(letter, null)
        }
        draft.value = current
        // Compact numbering in the group the item left.
        if (oldLetter != null && oldLetter != letter) compactGroup(oldLetter)
        if (oldLetter == letter) compactGroup(letter)
    }

    /**
     * Tap number chip [n] on [itemId] within group [letter].
     * Insert-and-shift: the item takes spot n, everything at n and
     * below moves down one. Tapping the item's own current number
     * removes it (and the rest compact).
     */
    fun setDraftNumber(itemId: String, letter: String, n: Int) {
        val current = draft.value.toMutableMap()
        val node = ListsRepo.node(nodeId) ?: return

        // The group's items in the list's current visual order.
        val groupIds = node.children
            .filter { current[it.id]?.letter == letter }
            .map { it.id }

        // Currently numbered items, in number order.
        val ordered = groupIds
            .filter { current[it]?.number != null }
            .sortedBy { current[it]!!.number }
            .toMutableList()

        val own = current[itemId]?.number
        if (own == n) {
            // Tapping your own number takes it off.
            ordered.remove(itemId)
        } else {
            ordered.remove(itemId)
            val insertAt = (n - 1).coerceIn(0, ordered.size)
            ordered.add(insertAt, itemId)
        }

        // Renumber 1..k, clear everyone else in the group.
        groupIds.forEach { id ->
            val idx = ordered.indexOf(id)
            val label = current[id] ?: DraftLabel()
            current[id] = label.copy(number = if (idx >= 0) idx + 1 else null)
        }
        draft.value = current
    }

    private fun compactGroup(letter: String) {
        val current = draft.value.toMutableMap()
        val ordered = current.entries
            .filter { it.value.letter == letter && it.value.number != null }
            .sortedBy { it.value.number }
            .map { it.key }
        ordered.forEachIndexed { i, id ->
            current[id] = current[id]!!.copy(number = i + 1)
        }
        draft.value = current
    }

    /** Advance from letters phase, or from a numbers group, applying at the end. */
    fun sortNext() {
        val groups = sortGroups()
        val m = mode.value
        when (m) {
            is Mode.SortLetters -> {
                if (groups.isEmpty()) finishSort() else mode.value = Mode.SortNumbers(0)
            }
            is Mode.SortNumbers -> {
                if (m.groupIndex + 1 >= groups.size) finishSort()
                else mode.value = Mode.SortNumbers(m.groupIndex + 1)
            }
            else -> {}
        }
    }

    /** Step back a group, or back to the letters phase. */
    fun sortBack() {
        val m = mode.value
        if (m is Mode.SortNumbers) {
            mode.value = if (m.groupIndex == 0) Mode.SortLetters
            else Mode.SortNumbers(m.groupIndex - 1)
        } else if (m is Mode.SortLetters) {
            cancelSort()
        }
    }

    private fun finishSort() {
        ListsRepo.applySort(nodeId, draft.value)
        draft.value = emptyMap()
        mode.value = Mode.Browse
    }

    fun clearSortLabels() {
        ListsRepo.clearSort(nodeId)
    }

    /* ---------- Backup ---------- */

    fun openBackup() {
        mode.value = Mode.Backup()
    }

    fun exportBackup(fileShare: com.thelightphone.sdk.LightFileShare) {
        val message = try {
            fileShare.write(BACKUP_FILE) { it.write(ListsRepo.exportJson()) }
            "Exported to shared folder:\n$BACKUP_FILE"
        } catch (e: Exception) {
            "Export failed."
        }
        mode.value = Mode.Backup(message = message)
    }

    fun importTapped(fileShare: com.thelightphone.sdk.LightFileShare) {
        val m = mode.value as? Mode.Backup
        if (m?.confirmImport == true) {
            val text = try {
                fileShare.read(BACKUP_FILE) { it.readText() }
            } catch (e: Exception) {
                null
            }
            val message = when {
                text == null -> "No backup file found:\n$BACKUP_FILE"
                ListsRepo.importJson(text) -> "Restored from backup."
                else -> "Backup file couldn't be read."
            }
            mode.value = Mode.Backup(message = message)
        } else {
            mode.value = Mode.Backup(confirmImport = true)
        }
    }

    companion object {
        const val BACKUP_FILE = "lists-backup.json"
    }
}

/* ---------------- Screens ---------------- */

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, NodeViewModel>(sealedActivity) {

    override val viewModelClass: Class<NodeViewModel> get() = NodeViewModel::class.java

    override fun createViewModel(): NodeViewModel {
        ListsRepo.init(lightContext.dataStore)
        return NodeViewModel(ROOT_ID)
    }

    val fileShareAccess get() = lightContext.fileShare

    @Composable
    override fun Content() {
        NodeContent(screen = this, viewModel = viewModel, isRoot = true, fileShare = fileShareAccess)
    }
}

class ListScreen(
    sealedActivity: SealedLightActivity,
    private val nodeId: String,
) : LightScreen<Unit, NodeViewModel>(sealedActivity) {

    override val viewModelClass: Class<NodeViewModel> get() = NodeViewModel::class.java

    override fun createViewModel(): NodeViewModel = NodeViewModel(nodeId)

    val fileShareAccess get() = lightContext.fileShare

    @Composable
    override fun Content() {
        NodeContent(screen = this, viewModel = viewModel, isRoot = false, fileShare = fileShareAccess)
    }
}

/* ---------------- Shared UI ---------------- */

@Composable
private fun NodeContent(
    screen: SimpleLightScreen<Unit>,
    viewModel: NodeViewModel,
    isRoot: Boolean,
    fileShare: com.thelightphone.sdk.LightFileShare,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    val root by ListsRepo.root.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val editMode by viewModel.editMode.collectAsState()
    val keyboardOptionsFlow = rememberKeyboardOptions()

    val node = root.find(viewModel.nodeId)

    // Breadcrumb favors the current location: FAMILY/WIFE/GIFTS, trimmed
    // from the front to …/WIFE/GIFTS when long. Home shows no title.
    val crumb = remember(root, viewModel.nodeId, isRoot) {
        if (isRoot) {
            ""
        } else {
            val chain = root.pathTo(viewModel.nodeId).orEmpty()
            // Drop chain[0] (the invisible root) entirely — don't show HOME.
            val names = chain.drop(1).map { it.name }
            buildBreadcrumb(names)
        }
    }

    LightTheme(colors = themeColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            when (val m = mode) {
                is Mode.Input -> {
                    key(m.session) {
                        val textState = rememberTextFieldState(m.initial)
                        NoteInputEditor(
                            title = m.title,
                            state = textState,
                            onSubmit = viewModel::submitInput,
                            onBack = viewModel::cancelInput,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            editorKey = m.session,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                is Mode.Options -> {
                    val item = node?.children?.firstOrNull { it.id == m.itemId }
                    if (item == null) {
                        BrowseContent(screen, viewModel, node, isRoot, editMode, crumb)
                    } else {
                        OptionsContent(viewModel, item)
                    }
                }

                is Mode.Settings -> {
                    if (node == null) {
                        BrowseContent(screen, viewModel, node, isRoot, editMode, crumb)
                    } else {
                        SettingsContent(viewModel, node, m.confirmPurge, m.confirmPurgeAll)
                    }
                }

                is Mode.SortLetters -> {
                    if (node == null) {
                        BrowseContent(screen, viewModel, node, isRoot, editMode, crumb)
                    } else {
                        SortLettersContent(viewModel, node)
                    }
                }

                is Mode.SortNumbers -> {
                    val groups = viewModel.sortGroups()
                    val letter = groups.getOrNull(m.groupIndex)
                    if (node == null || letter == null) {
                        BrowseContent(screen, viewModel, node, isRoot, editMode, crumb)
                    } else {
                        SortNumbersContent(viewModel, node, letter, m.groupIndex, groups.size)
                    }
                }

                is Mode.Backup -> {
                    BackupContent(viewModel, m, fileShare)
                }

                is Mode.MoveTarget -> {
                    MoveTargetContent(viewModel, root, m)
                }

                is Mode.Browse -> {
                    BrowseContent(screen, viewModel, node, isRoot, editMode, crumb)
                }
            }
        }
    }
}

@Composable
private fun BrowseContent(
    screen: SimpleLightScreen<Unit>,
    viewModel: NodeViewModel,
    node: Node?,
    isRoot: Boolean,
    editMode: Boolean,
    crumb: String,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // On the home screen there's no title. Skip the top bar entirely
        // (unless editing, where we still want the EDIT label) so the list
        // uses the full window instead of leaving an empty strip.
        val showTopBar = !crumb.isEmpty() || editMode || !isRoot
        if (showTopBar) {
            LightTopBar(
                leftButton = if (isRoot) null else LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { screen.goBack() },
                ),
                center = if (editMode) {
                    if (crumb.isEmpty()) {
                        LightTopBarCenter.Text("EDIT")
                    } else {
                        LightTopBarCenter.TwoLineDetail(
                            line1 = crumb,
                            line2 = "EDIT",
                        )
                    }
                } else {
                    LightTopBarCenter.Text(crumb)
                },
                rightButton = null,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
        } else {
            // Small breathing room from the very top edge.
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
        }

        val clip by ListsRepo.clipboard.collectAsState()
        if (editMode && clip != null) {
            OptionRow("PASTE HERE: ${clip!!.name}") { viewModel.pasteHere() }
        }

        Box(modifier = Modifier.weight(1f)) {
            val children = node?.children.orEmpty()
            if (children.isEmpty()) {
                LightText(
                    text = if (isRoot) {
                        "No lists yet.\nTap + to add one."
                    } else {
                        "Empty.\nTap + to add items."
                    },
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                )
            } else {
                LightScrollView(modifier = Modifier.fillMaxSize()) {
                    children.forEach { child ->
                        ItemRow(
                            item = child,
                            checklist = node?.checklist == true,
                            editMode = editMode,
                            onToggleCheck = { viewModel.toggleChecked(child.id) },
                            onDelete = { viewModel.deleteItem(child.id) },
                            onTap = {
                                if (editMode) {
                                    viewModel.openOptions(child.id)
                                } else {
                                    screen.navigateTo({ sealed ->
                                        ListScreen(sealed, child.id)
                                    })
                                }
                            },
                        )
                    }
                }
            }
        }

        // Custom 4-icon bar (metrics matched to LightBottomBar).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 1f.gridUnitsAsDp())
                .height(4f.gridUnitsAsDp())
                .padding(horizontal = 2f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val haptic = LocalHapticFeedback.current
            LightIcon(
                icon = LightIcons.SETTINGS,
                size = 2f,
                modifier = Modifier.lightClickable(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.openSettings()
                }),
                contentDescription = "settings",
            )
            Box(
                modifier = Modifier
                    .lightClickable(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleEdit()
                    })
                    .height(4f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = if (editMode) "DONE" else "EDIT",
                    variant = LightTextVariant.Button,
                    maxLines = 1,
                )
            }
            LightIcon(
                icon = LightIcons.COMPOSE_MESSAGE,
                size = 2f,
                modifier = Modifier.lightClickable(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.startAdd()
                }),
                contentDescription = "new item",
            )
        }
    }
}

@Composable
private fun ItemRow(
    item: Node,
    checklist: Boolean,
    editMode: Boolean,
    onToggleCheck: () -> Unit,
    onDelete: () -> Unit,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val rowHaptic = LocalHapticFeedback.current
        if (editMode) {
            Box(
                modifier = Modifier
                    .padding(end = 1f.gridUnitsAsDp())
                    .size(28.dp)
                    .border(1.dp, LightThemeTokens.colors.content, CircleShape)
                    .lightClickable(onClick = {
                        rowHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                    }),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.CLOSE,
                    size = 1.2f,
                )
            }
        }

        if (checklist) {
            Box(
                modifier = Modifier
                    .padding(end = 1f.gridUnitsAsDp())
                    .lightClickable(onClick = {
                        rowHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleCheck()
                    })
                    .padding(vertical = 1f.gridUnitsAsDp()),
            ) {
                PencilCircle(
                    checked = item.checked,
                    seed = item.id.hashCode(),
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onTap)
                .padding(vertical = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val contentColor = LightThemeTokens.colors.content

            // Sort label prefix, like "A1"
            val label = sortLabelText(item)
            if (label != null) {
                LightText(
                    text = label,
                    variant = LightTextVariant.Copy,
                    color = contentColor,
                    modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                LightText(
                    text = item.name,
                    variant = LightTextVariant.Copy,
                    modifier = if (checklist && item.checked) {
                        Modifier.drawWithContent {
                            drawContent()
                            // One thin pencil line through the text —
                            // slight tilt and offset, stable per item.
                            val rnd = kotlin.random.Random(seed = item.id.hashCode() + 7)
                            val midY = size.height / 2f + (rnd.nextFloat() - 0.5f) * 4f
                            val tilt = (rnd.nextFloat() - 0.5f) * 5f
                            drawLine(
                                color = contentColor,
                                start = Offset(-2f, midY + tilt),
                                end = Offset(size.width + 2f, midY - tilt),
                                strokeWidth = 1.3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        Modifier
                    },
                )
            }
            if (item.children.isNotEmpty()) {
                if (item.children.size > 1) {
                    LightText(
                        text = "${item.children.size}",
                        variant = LightTextVariant.Copy,
                        color = LightThemeTokens.colors.content,
                        modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
                    )
                }
                LightIcon(
                    icon = LightIcons.ARROW_RIGHT,
                    size = 1.4f,
                )
            }
        }
    }
}

private fun sortLabelText(item: Node): String? {
    val letter = item.sortLetter ?: return null
    return letter + (item.sortNumber?.toString() ?: "")
}

/* ---------------- Sort flow UI ---------------- */

@Composable
private fun SortLettersContent(
    viewModel: NodeViewModel,
    node: Node,
) {
    val draft by viewModel.draft.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = viewModel::cancelSort,
            ),
            center = LightTopBarCenter.TwoLineDetail(
                line1 = node.name.uppercase(),
                line2 = "ASSIGN LETTERS",
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.weight(1f)) {
            node.children.forEach { child ->
                val assigned = draft[child.id]?.letter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = child.name,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.weight(1f),
                    )
                    SORT_LETTERS.forEach { letter ->
                        SortChip(
                            label = letter,
                            selected = assigned == letter,
                            onClick = { viewModel.setDraftLetter(child.id, letter) },
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = "CANCEL",
                    onClick = viewModel::cancelSort,
                ),
                LightBarButton.Text(
                    text = if (viewModel.sortGroups().isEmpty()) "DONE" else "NEXT",
                    onClick = viewModel::sortNext,
                ),
            ),
        )
    }
}

@Composable
private fun SortNumbersContent(
    viewModel: NodeViewModel,
    node: Node,
    letter: String,
    groupIndex: Int,
    groupCount: Int,
) {
    val draft by viewModel.draft.collectAsState()

    val groupItems = node.children.filter { draft[it.id]?.letter == letter }
    val groupSize = groupItems.size
    val isLastGroup = groupIndex + 1 >= groupCount

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = viewModel::sortBack,
            ),
            center = LightTopBarCenter.TwoLineDetail(
                line1 = node.name.uppercase(),
                line2 = "GROUP $letter — ORDER",
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.weight(1f)) {
            groupItems.forEach { child ->
                val myNumber = draft[child.id]?.number
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LightText(
                            text = if (myNumber != null) "$letter$myNumber" else "",
                            variant = LightTextVariant.Copy,
                            color = LightThemeTokens.colors.content,
                            modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = child.name,
                            variant = LightTextVariant.Copy,
                        )
                    }
                    // Number chips, wrapping onto extra rows of 7 if needed
                    (1..groupSize).chunked(7).forEach { rowNumbers ->
                        Row(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp())) {
                            rowNumbers.forEach { n ->
                                SortChip(
                                    label = "$n",
                                    selected = myNumber == n,
                                    onClick = {
                                        viewModel.setDraftNumber(child.id, letter, n)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = "CANCEL",
                    onClick = viewModel::cancelSort,
                ),
                LightBarButton.Text(
                    text = if (isLastGroup) "DONE" else "NEXT",
                    onClick = viewModel::sortNext,
                ),
            ),
        )
    }
}

/** A small square tappable chip. Selected = solid white with black text. */
@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val content = LightThemeTokens.colors.content
    val background = LightThemeTokens.colors.background
    Box(
        modifier = Modifier
            .padding(start = 0.75f.gridUnitsAsDp())
            .size(34.dp)
            .lightClickable(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
            .background(if (selected) content else background)
            .border(1.dp, content),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            color = if (selected) background else content,
        )
    }
}

/* ---------------- Options / Settings ---------------- */

@Composable
private fun MoveTargetContent(
    viewModel: NodeViewModel,
    root: Node,
    mode: Mode.MoveTarget,
) {
    val browseNode = root.find(mode.browseId)
    val moving = root.find(mode.itemId)
    // Where does the item currently live? Can't move it into itself/descendants.
    val crumbNames = root.pathTo(mode.browseId).orEmpty()
        .drop(1).map { it.name }
    val crumb = buildBreadcrumb(crumbNames)

    // Children we can descend into as destinations (not the item itself,
    // and not inside the item's own subtree).
    val destinations = browseNode?.children.orEmpty()
        .filter { it.id != mode.itemId && moving?.find(it.id) == null }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = viewModel::moveBrowseUp,
            ),
            center = LightTopBarCenter.TwoLineDetail(
                line1 = crumb,
                line2 = "MOVE HERE?",
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.weight(1f)) {
            LightText(
                text = "Moving: ${moving?.name ?: ""}",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
            )

            if (destinations.isEmpty()) {
                LightText(
                    text = "No sub-lists here. Move into this one, or go back.",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                )
            } else {
                LightText(
                    text = "Or open a list below to go deeper:",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                )
                destinations.forEach { dest ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable(onClick = { viewModel.moveBrowseInto(dest.id) })
                            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightText(
                            text = dest.name,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(
                            text = "OPEN",
                            variant = LightTextVariant.Detail,
                            color = LightThemeTokens.colors.content,
                            modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
                        )
                        LightIcon(
                            icon = LightIcons.ARROW_RIGHT,
                            size = 1.4f,
                        )
                    }
                }
            }
        }

        // Confirm button pinned at the bottom.
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = "CANCEL",
                    onClick = viewModel::backToBrowse,
                ),
                LightBarButton.Text(
                    text = "MOVE HERE",
                    onClick = viewModel::confirmMove,
                ),
            ),
        )
    }
}

@Composable
private fun OptionsContent(
    viewModel: NodeViewModel,
    item: Node,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = viewModel::backToBrowse,
            ),
            center = LightTopBarCenter.Text(item.name.uppercase()),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.fillMaxSize()) {
            OptionRow("RENAME") { viewModel.startRename(item) }
            OptionRow("MOVE UP") { viewModel.moveItem(item.id, up = true) }
            OptionRow("MOVE DOWN") { viewModel.moveItem(item.id, up = false) }
            OptionRow("MOVE TO LIST…") { viewModel.startMove(item.id) }
            OptionRow("COPY") { viewModel.copyItem(item.id) }
        }
    }
}

@Composable
private fun SettingsContent(
    viewModel: NodeViewModel,
    node: Node,
    confirmPurge: Boolean,
    confirmPurgeAll: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = viewModel::backToBrowse,
            ),
            center = LightTopBarCenter.TwoLineDetail(
                line1 = node.name.uppercase(),
                line2 = "SETTINGS",
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.fillMaxSize()) {
            // Checklist: this list only
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = viewModel::toggleChecklist)
                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = "CHECKLIST (LIST ONLY)",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.weight(1f),
                )
                LightIcon(
                    icon = if (node.checklist) LightIcons.TOGGLE_STATE_OFF else LightIcons.TOGGLE_STATE_ON,
                )
            }

            // Checklist: this list and everything nested inside it
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = viewModel::toggleChecklistAll)
                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = "CHECKLIST (ENTIRE GROUP)",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.weight(1f),
                )
                LightIcon(
                    icon = if (node.isAllChecklist()) LightIcons.TOGGLE_STATE_OFF else LightIcons.TOGGLE_STATE_ON,
                )
            }

            // Purge checked items (only when this list is a checklist)
            if (node.checklist) {
                val checkedCount = node.children.count { it.checked }
                OptionRow(
                    if (confirmPurge) {
                        "TAP AGAIN TO PURGE LIST ($checkedCount)"
                    } else {
                        "PURGE CHECKED (LIST ONLY) ($checkedCount)"
                    }
                ) { viewModel.purgeTapped() }
            }

            // Purge checked items across the whole group (any checklist inside)
            if (node.hasAnyChecklist()) {
                val checkedAll = node.countCheckedAll()
                OptionRow(
                    if (confirmPurgeAll) {
                        "TAP AGAIN TO PURGE GROUP ($checkedAll)"
                    } else {
                        "PURGE CHECKED (ENTIRE GROUP) ($checkedAll)"
                    }
                ) { viewModel.purgeAllTapped() }
            }

            // Sort this list (only meaningful when it has items)
            if (node.children.isNotEmpty()) {
                OptionRow("SORT LIST") { viewModel.startSort() }
            }

            // Clear sort labels (only when the list has any)
            if (node.children.any { it.sortLetter != null }) {
                OptionRow("CLEAR SORT") { viewModel.clearSortLabels() }
            }

            // Backup / restore (offered from the home screen root)
            OptionRow("BACKUP / RESTORE") { viewModel.openBackup() }
        }
    }
}

@Composable
private fun BackupContent(
    viewModel: NodeViewModel,
    mode: Mode.Backup,
    fileShare: com.thelightphone.sdk.LightFileShare,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = viewModel::backToBrowse,
            ),
            center = LightTopBarCenter.Text("BACKUP / RESTORE"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.weight(1f)) {
            LightText(
                text = "Export saves all your lists to a file in the phone's shared folder. Restore reads that file back, replacing everything.",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
            )

            OptionRow("EXPORT (SAVE BACKUP)") { viewModel.exportBackup(fileShare) }

            OptionRow(
                if (mode.confirmImport) "TAP AGAIN TO RESTORE (REPLACES ALL)"
                else "RESTORE (LOAD BACKUP)"
            ) { viewModel.importTapped(fileShare) }

            if (mode.message != null) {
                LightText(
                    text = mode.message,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 2f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val colors = LightThemeTokens.colors
    var flash by remember { mutableStateOf(false) }
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        color = if (flash) colors.background else null,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    flash = true
                    delay(150)
                    flash = false
                }
                onClick()
            })
            .background(if (flash) colors.contentSecondary else colors.background)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
    )
}

/**
 * A hand-drawn looking checkbox: a circle outline that, when checked,
 * is filled with pencil-style scribble strokes. The [seed] keeps each
 * item's scribble unique but stable between redraws.
 * Strokes are full-brightness white — the pencil texture comes from
 * small black gaps between them, not from gray.
 */
@Composable
private fun PencilCircle(
    checked: Boolean,
    seed: Int,
    modifier: Modifier = Modifier,
) {
    val color = LightThemeTokens.colors.content
    Canvas(modifier = modifier) {
        val strokeW = 1.5.dp.toPx()
        val radius = size.minDimension / 2f - strokeW
        val c = center

        // Outline
        drawCircle(
            color = color,
            radius = radius,
            center = c,
            style = Stroke(width = strokeW),
        )

        if (!checked) return@Canvas

        val rnd = kotlin.random.Random(seed)
        val clip = Path().apply {
            addOval(Rect(center = c, radius = radius - strokeW * 0.3f))
        }

        clipPath(clip) {
            // Main diagonal hatch strokes (bottom-left to top-right),
            // with jittered spacing, width, and endpoints.
            val step = 3.2.dp.toPx()
            var x = -size.height
            while (x < size.width + size.height) {
                val j1 = (rnd.nextFloat() - 0.5f) * step * 0.8f
                val j2 = (rnd.nextFloat() - 0.5f) * step * 0.8f
                drawLine(
                    color = color,
                    start = Offset(x + j1, size.height + rnd.nextFloat() * 3f),
                    end = Offset(x + size.height + j2, -rnd.nextFloat() * 3f),
                    strokeWidth = strokeW * (0.6f + rnd.nextFloat() * 0.8f),
                    cap = StrokeCap.Round,
                )
                x += step * (0.7f + rnd.nextFloat() * 0.6f)
            }

            // A few crossing strokes the other way, like going back
            // over it with the pencil.
            repeat(3 + rnd.nextInt(3)) {
                val x0 = rnd.nextFloat() * size.width
                val j1 = (rnd.nextFloat() - 0.5f) * step
                val j2 = (rnd.nextFloat() - 0.5f) * step
                drawLine(
                    color = color,
                    start = Offset(x0 + j1, -2f),
                    end = Offset(x0 - size.height + j2, size.height + 2f),
                    strokeWidth = strokeW * (0.5f + rnd.nextFloat() * 0.6f),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

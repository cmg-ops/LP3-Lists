package com.dailyhobbyist.lists

import kotlinx.serialization.Serializable

/**
 * Everything in the app is a Node.
 *
 * - The invisible ROOT node's children are the lists shown on the home screen.
 * - Each list's children are its items.
 * - Each item can itself have children — that's how an item "expands"
 *   into a new list inside it, as deep as you want.
 */
@Serializable
data class Node(
    val id: String,
    val name: String,
    val children: List<Node> = emptyList(),
    val checklist: Boolean = false,
    val checked: Boolean = false,
    val sortLetter: String? = null,
    val sortNumber: Int? = null,
)

/** Working copy of one item's sort label while the sort flow is open. */
data class DraftLabel(
    val letter: String? = null,
    val number: Int? = null,
)

const val ROOT_ID = "root"

fun newId(): String = java.util.UUID.randomUUID().toString()

/** Find a node anywhere in the tree by id. */
fun Node.find(targetId: String): Node? {
    if (id == targetId) return this
    for (child in children) {
        child.find(targetId)?.let { return it }
    }
    return null
}

/** Return a copy of the tree with a new child appended under [parentId]. */
fun Node.withChildAdded(parentId: String, child: Node): Node {
    if (id == parentId) return copy(children = children + child)
    return copy(children = children.map { it.withChildAdded(parentId, child) })
}

/** Return a copy of the tree with node [targetId] renamed. */
fun Node.withRenamed(targetId: String, newName: String): Node {
    if (id == targetId) return copy(name = newName)
    return copy(children = children.map { it.withRenamed(targetId, newName) })
}

/** Return a copy of the tree with node [targetId] transformed by [transform]. */
fun Node.withUpdated(targetId: String, transform: (Node) -> Node): Node {
    if (id == targetId) return transform(this)
    return copy(children = children.map { it.withUpdated(targetId, transform) })
}

/** Return a copy of this node with checklist set to [on] for itself and everything inside it. */
fun Node.withAllChecklist(on: Boolean): Node =
    copy(checklist = on, children = children.map { it.withAllChecklist(on) })

/** True if this node and every node nested inside it is a checklist. */
fun Node.isAllChecklist(): Boolean =
    checklist && children.all { it.isAllChecklist() }

/** True if this node or anything nested inside it is a checklist. */
fun Node.hasAnyChecklist(): Boolean =
    checklist || children.any { it.hasAnyChecklist() }

/** Return a copy of this node with every checked item removed, at every level. */
fun Node.withAllCheckedPurged(): Node =
    copy(children = children.filter { !it.checked }.map { it.withAllCheckedPurged() })

/** Count every checked item in this node's group, at every level. */
fun Node.countCheckedAll(): Int =
    children.sumOf { (if (it.checked) 1 else 0) + it.countCheckedAll() }

/** Return a copy of the tree with node [targetId] removed (and everything inside it). */
fun Node.withDeleted(targetId: String): Node {
    return copy(
        children = children
            .filter { it.id != targetId }
            .map { it.withDeleted(targetId) }
    )
}

/** Return a copy of the tree with the child at [fromIndex] under [parentId] moved to [toIndex]. */
fun Node.withChildMoved(parentId: String, fromIndex: Int, toIndex: Int): Node {
    if (id == parentId) {
        if (fromIndex !in children.indices || toIndex !in children.indices) return this
        val mutable = children.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return copy(children = mutable)
    }
    return copy(children = children.map { it.withChildMoved(parentId, fromIndex, toIndex) })
}

/** The chain of nodes from this node down to [targetId], inclusive. Null if not found. */
fun Node.pathTo(targetId: String): List<Node>? {
    if (id == targetId) return listOf(this)
    for (child in children) {
        val sub = child.pathTo(targetId)
        if (sub != null) return listOf(this) + sub
    }
    return null
}

/** Find the id of the node whose direct children include [childId]. */
fun Node.parentOf(childId: String): String? {
    if (children.any { it.id == childId }) return id
    for (child in children) {
        child.parentOf(childId)?.let { return it }
    }
    return null
}

/** Detach [childId] from wherever it lives and append it under [newParentId]. */
fun Node.withNodeMoved(childId: String, newParentId: String): Node {
    val moving = find(childId) ?: return this
    if (childId == newParentId) return this
    // Don't allow moving a node into its own descendant.
    if (moving.find(newParentId) != null) return this
    return withDeleted(childId).withChildAdded(newParentId, moving)
}

/** A deep copy of this node with brand-new ids throughout (for paste). */
fun Node.deepCopyWithNewIds(): Node =
    copy(id = newId(), children = children.map { it.deepCopyWithNewIds() })

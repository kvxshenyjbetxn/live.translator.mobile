package dev.live.translator.mobile.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import dev.live.translator.mobile.R
import dev.live.translator.mobile.model.CaptureTarget

class AppPickerAdapter(
    private val packageManager: PackageManager,
    private val onSelected: (CaptureTarget) -> Unit,
    private val onPinToggled: (CaptureTarget) -> Unit,
) : BaseAdapter() {
    private sealed class Entry {
        class Header(val title: String) : Entry()
        class Row(val target: CaptureTarget) : Entry()
    }

    private var entries = emptyList<Entry>()
    private var selectedPackage: String? = null
    private var pinned = emptySet<String>()
    private val icons = HashMap<String, Drawable?>()

    fun submit(sections: List<TargetSection>, selected: String?, pinnedPackages: Collection<String>) {
        entries = sections.flatMap { s -> listOf<Entry>(Entry.Header(s.title)) + s.targets.map { Entry.Row(it) } }
        selectedPackage = selected
        pinned = pinnedPackages.toSet()
        notifyDataSetChanged()
    }

    private fun icon(packageName: String): Drawable? = icons.getOrPut(packageName) {
        try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { packageManager.defaultActivityIcon }
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (entries[position] is Entry.Header) 0 else 1
    override fun areAllItemsEnabled(): Boolean = false
    override fun isEnabled(position: Int): Boolean = (entries[position] as? Entry.Row)?.target?.available == true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(parent.context)
        return when (val entry = entries[position]) {
            is Entry.Header -> ((convertView ?: inflater.inflate(R.layout.item_picker_header, parent, false)) as TextView).also { it.text = entry.title }
            is Entry.Row -> {
                val row = convertView ?: inflater.inflate(R.layout.item_capture_target, parent, false)
                val target = entry.target
                val isPinned = target.packageName in pinned
                row.findViewById<ImageView>(R.id.targetIcon).setImageDrawable(icon(target.packageName))
                row.findViewById<TextView>(R.id.targetLabel).text = target.label
                row.findViewById<TextView>(R.id.targetPackage).text = target.packageName
                row.findViewById<TextView>(R.id.targetStatus).text = when {
                    !target.available -> "недоступна"
                    target.packageName == selectedPackage -> "вибрано"
                    else -> ""
                }
                row.isSelected = target.packageName == selectedPackage
                row.alpha = if (target.available) 1f else 0.4f
                row.contentDescription = null
                row.findViewById<TextView>(R.id.pinToggle).apply {
                    text = if (isPinned) "★" else "☆"
                    setTextColor(context.getColor(if (isPinned) R.color.accent else R.color.muted))
                    contentDescription = if (isPinned) "Відкріпити" else "Закріпити"
                    setOnClickListener { onPinToggled(target) }
                }
                row
            }
        }
    }

    fun onItemClick(position: Int) {
        val row = entries.getOrNull(position) as? Entry.Row ?: return
        if (row.target.available) onSelected(row.target)
    }
}

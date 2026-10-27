package dev.live.translator.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import dev.live.translator.mobile.R
import dev.live.translator.mobile.model.CaptureTarget

class AppPickerAdapter(
    private var values: List<CaptureTarget> = emptyList(),
    private val onSelected: (CaptureTarget) -> Unit,
) : BaseAdapter() {
    fun submitList(next: List<CaptureTarget>) { values = next; notifyDataSetChanged() }
    override fun getCount(): Int = values.size
    override fun getItem(position: Int): CaptureTarget = values[position]
    override fun getItemId(position: Int): Long = values[position].packageName.hashCode().toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_capture_target, parent, false)
        val target = getItem(position)
        row.findViewById<TextView>(R.id.targetLabel).text = target.label
        row.findViewById<TextView>(R.id.targetPackage).text = target.packageName
        row.isSelected = target.available
        row.contentDescription = target.label
        row.setOnClickListener { onSelected(target) }
        return row
    }
}

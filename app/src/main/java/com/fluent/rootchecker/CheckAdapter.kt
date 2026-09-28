package com.fluent.rootchecker

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fluent.rootchecker.databinding.ItemCheckBinding

/** Список результатов проверок с каскадной анимацией появления новых карточек. */
class CheckAdapter : RecyclerView.Adapter<CheckAdapter.Holder>() {

    private val items = mutableListOf<RootCheckResult>()
    private var animatedUpTo = 0

    fun append(result: RootCheckResult) {
        items.add(result)
        notifyItemInserted(items.size - 1)
    }

    fun reset() {
        items.clear()
        animatedUpTo = 0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCheckBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val isNew = position >= animatedUpTo
        holder.bind(items[position], isNew)
        if (isNew) animatedUpTo = position + 1
    }

    class Holder(private val binding: ItemCheckBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RootCheckResult, animate: Boolean) {
            val context = binding.root.context

            val (iconRes, colorRes, statusText) = when (item.status) {
                CheckStatus.SAFE -> Triple(
                    R.drawable.ic_check_circle,
                    R.color.status_safe,
                    context.getString(R.string.status_ok)
                )
                CheckStatus.ROOT -> Triple(
                    R.drawable.ic_error,
                    R.color.status_root,
                    context.getString(R.string.status_root)
                )
                CheckStatus.WARNING -> Triple(
                    R.drawable.ic_warning,
                    R.color.status_warn,
                    context.getString(R.string.status_warn)
                )
                CheckStatus.UNKNOWN -> Triple(
                    R.drawable.ic_help,
                    R.color.status_unknown,
                    context.getString(R.string.status_unknown)
                )
            }
            val color = ContextCompat.getColor(context, colorRes)

            binding.itemIcon.setImageResource(iconRes)
            binding.itemIcon.setColorFilter(color)
            binding.itemTitle.text = item.title
            binding.itemDetail.text = buildString {
                append(item.description)
                if (item.detail.isNotEmpty()) {
                    append('\n')
                    append(item.detail)
                }
            }

            binding.itemChip.text = statusText
            binding.itemChip.setTextColor(color)
            val alphaColor = Color.argb(
                0x26,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            binding.itemChip.backgroundTintList = ColorStateList.valueOf(alphaColor)

            if (animate) {
                binding.root.alpha = 0f
                binding.root.translationY = 26f
                binding.root
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280)
                    .setStartDelay(40)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                binding.root.animate().cancel()
                binding.root.alpha = 1f
                binding.root.translationY = 0f
            }
        }
    }
}

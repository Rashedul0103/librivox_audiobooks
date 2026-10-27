package com.rashedul.audible

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainActivity
import org.json.JSONArray

/**
 * Per-device settings for the Audible CloudStream provider.
 *
 * CloudStream exposes a plugin settings button through Plugin.openSettings.
 * These values use CloudStream's own key-value store so they survive app
 * restarts and plugin updates.
 */
object AudibleSettings {
    data class Shelf(val id: String, val title: String)

    const val FIT_CONTAIN = "contain"
    const val FIT_STRETCH = "fill"
    const val FIT_CROP = "cover"

    private const val PREFIX = "AUDIBLE_UI_V1_"
    private const val ORDER_KEY = PREFIX + "ROW_ORDER"
    private const val FIT_KEY = PREFIX + "COVER_FIT"

    val shelves = listOf(
        Shelf("new_releases", "New Releases"),
        Shelf("popular", "Popular Audiobooks"),
        Shelf("recent_metadata", "Recently Updated Metadata"),
        Shelf("genre_scifi", "Science Fiction"),
        Shelf("genre_fantasy", "Fantasy"),
        Shelf("genre_mystery", "Mystery, Thriller & Suspense"),
        Shelf("genre_romance", "Romance"),
        Shelf("genre_horror", "Horror"),
        Shelf("genre_historical", "Historical Fiction"),
        Shelf("genre_biography", "Biography & Memoir"),
        Shelf("genre_self_help", "Self-Help & Personal Development"),
        Shelf("genre_business", "Business & Careers"),
        Shelf("genre_young_adult", "Teen & Young Adult"),
        Shelf("genre_children", "Children & Family"),
        Shelf("genre_religion", "Religion & Spirituality"),
        Shelf("abridged", "Abridged Audiobooks"),
        Shelf("unabridged", "Unabridged Audiobooks"),
        Shelf("virtual_voice", "Virtual Voice (AI Narrated)"),
        Shelf("short_listens", "Short Audiobooks (Under 3 Hours)"),
        Shelf("long_listens", "Long Listens (10+ Hours)"),
        Shelf("full_cast", "Full Cast & Ensemble"),
        Shelf("collections", "Collections & Box Sets"),
        Shelf("dramatizations", "Dramatizations & Audio Dramas")
    )

    private val shelvesByTitle = shelves.associateBy { it.title }

    private fun enabledKey(id: String) = PREFIX + "ROW_ENABLED_" + id

    fun isShelfEnabled(id: String): Boolean = getKey<Boolean>(enabledKey(id)) ?: true

    fun coverFitMode(): String {
        val stored = getKey<String>(FIT_KEY)
        return when (stored) {
            FIT_STRETCH, FIT_CROP -> stored
            else -> FIT_CONTAIN
        }
    }

    fun orderedShelves(): List<Shelf> {
        val defaults = shelves.map { it.id }
        val stored = getKey<String>(ORDER_KEY)
        val ids = try {
            if (stored.isNullOrBlank()) emptyList()
            else {
                val array = JSONArray(stored)
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        val valid = ids.filter { id -> defaults.contains(id) }.distinct()
        val merged = valid + defaults.filterNot { valid.contains(it) }
        val byId = shelves.associateBy { it.id }
        return merged.mapNotNull { byId[it] }
    }

    /** Applies enabled flags and the user's ordering to the rows returned by Libex. */
    fun arrangeHomeRows(rows: List<HomePageList>): List<HomePageList> {
        val order = orderedShelves().mapIndexed { index, shelf -> shelf.title to index }.toMap()
        return rows
            .filter { row ->
                val shelf = shelvesByTitle[row.name]
                shelf == null || isShelfEnabled(shelf.id)
            }
            .sortedBy { row -> order[row.name] ?: Int.MAX_VALUE }
    }

    fun showSettings(context: Context) {
        val orderDraft = orderedShelves().toMutableList()
        val enabledDraft = shelves.filter { isShelfEnabled(it.id) }
            .mapTo(mutableSetOf()) { it.id }
        var fitDraft = coverFitMode()

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        content.addView(TextView(context).apply {
            text = "HOME ROWS"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(6))
        })

        content.addView(TextView(context).apply {
            text = "Switch rows on or off. Hold a row and drag it to change the order."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        val rowAdapter = ShelfAdapter(orderDraft, enabledDraft, context)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rowAdapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        }
        content.addView(list)

        val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from == to) return false
                val moved = orderDraft.removeAt(from)
                orderDraft.add(to, moved)
                rowAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        dragHelper.attachToRecyclerView(list)
        rowAdapter.startDrag = { holder -> dragHelper.startDrag(holder) }

        content.addView(sectionTitle(context, "COVER FITTING", dp))

        val fitGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            val options = listOf(
                FIT_CONTAIN to "Show the full cover — no crop (recommended)",
                FIT_STRETCH to "Fill the portrait card by stretching the image",
                FIT_CROP to "Crop to fill the portrait card"
            )
            options.forEach { (value, label) ->
                val button = RadioButton(context).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = 14f
                    tag = value
                    isChecked = value == fitDraft
                }
                addView(button)
            }
            setOnCheckedChangeListener { group, checkedId ->
                val selected = group.findViewById<RadioButton>(checkedId)
                fitDraft = selected?.tag as? String ?: FIT_CONTAIN
            }
        }
        content.addView(fitGroup)

        content.addView(TextView(context).apply {
            text = "The fit option changes the artwork inside CloudStream's existing portrait card. It cannot change the card's dimensions."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })

        content.addView(sectionTitle(context, "CORNER ROUNDING", dp))
        content.addView(TextView(context).apply {
            text = "The outer corner radius is drawn by CloudStream/CloudX itself. The plugin API does not expose a per-extension radius control, so a true corner-rounding slider requires an app-side change. The image-fit setting above is supported."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(content)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Audible Settings")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                shelves.forEach { shelf ->
                    setKey(enabledKey(shelf.id), enabledDraft.contains(shelf.id))
                }
                val json = JSONArray().apply {
                    orderDraft.forEach { put(it.id) }
                }
                setKey(ORDER_KEY, json.toString())
                setKey(FIT_KEY, fitDraft)
                runCatching { MainActivity.reloadHomeEvent.invoke(true) }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun sectionTitle(
        context: Context,
        title: String,
        dp: (Int) -> Int
    ): TextView = TextView(context).apply {
        text = title
        textSize = 13f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(12), 0, dp(4))
    }

    private class ShelfAdapter(
        val items: MutableList<Shelf>,
        private val enabled: MutableSet<String>,
        private val context: Context
    ) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

        class Holder(val box: LinearLayout, val checkbox: CheckBox, val handle: TextView) :
            RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()

            val box = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val checkbox = CheckBox(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 13.5f
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val handle = TextView(context).apply {
                text = "☰"
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                contentDescription = "Hold and drag to reorder"
                isFocusable = false
            }
            box.addView(checkbox)
            box.addView(handle)
            return Holder(box, checkbox, handle)
        }

        var startDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val shelf = items[position]
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.text = shelf.title
            holder.checkbox.isChecked = enabled.contains(shelf.id)
            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) enabled.add(shelf.id) else enabled.remove(shelf.id)
            }
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    startDrag?.invoke(holder)
                    true
                } else {
                    false
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}

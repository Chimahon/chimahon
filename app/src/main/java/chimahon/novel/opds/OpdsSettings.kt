package chimahon.novel.opds

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal object OpdsSettings {
    fun populate(screen: PreferenceScreen, store: OpdsStore) {
        val context = screen.context
        screen.removeAll()
        screen.addPreference(Preference(context).apply {
            title = context.getString(R.string.opds_add_catalog)
            setOnPreferenceClickListener {
                edit(screen, store, null)
                true
            }
        })
        store.catalogs().forEach { catalog ->
            val group = PreferenceCategory(context).apply { title = catalog.name }
            screen.addPreference(group)
            group.addPreference(Preference(context).apply {
                title = context.getString(R.string.opds_catalog_url)
                // Feed URLs may include an auth key. Only show the server host.
                summary = catalog.url.toHttpUrlOrNull()?.host
                setOnPreferenceClickListener { edit(screen, store, catalog); true }
            })
            group.addPreference(Preference(context).apply {
                title = context.getString(R.string.opds_remove_catalog)
                summary = context.getString(R.string.opds_remove_catalog_summary)
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context).setTitle(title).setMessage(summary)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> store.remove(catalog.id); populate(screen, store) }
                        .show()
                    true
                }
            })
        }
    }

    private fun edit(screen: PreferenceScreen, store: OpdsStore, catalog: OpdsCatalog?) {
        val context = screen.context
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        layout.addView(TextView(context).apply {
            text = context.getString(R.string.opds_kavita_setup_help)
            setPadding(0, padding / 2, 0, padding / 2)
        })
        fun field(label: Int, value: String, input: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
            hint = context.getString(label)
            inputType = input
            setText(value)
            layout.addView(this)
        }
        val name = field(R.string.opds_catalog_name, catalog?.name.orEmpty())
        val url = field(R.string.opds_catalog_url, catalog?.url.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val username = field(R.string.opds_username, catalog?.username.orEmpty())
        val password = field(R.string.opds_password, catalog?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dialog = AlertDialog.Builder(context).setTitle(catalog?.name ?: context.getString(R.string.opds_add_catalog))
            .setView(ScrollView(context).apply { addView(layout) })
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = url.text.toString().trim().toHttpUrlOrNull()
                if (parsed == null || name.text.isBlank()) {
                    url.error = context.getString(R.string.opds_invalid_url)
                } else {
                    store.save(OpdsCatalog(
                        id = catalog?.id ?: UUID.randomUUID().toString(),
                        name = name.text.toString().trim(),
                        url = parsed.toString(),
                        username = username.text.toString(),
                        password = password.text.toString(),
                    ))
                    populate(screen, store)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}

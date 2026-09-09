package eu.kanade.tachiyomi.ui.browse.novel

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.preference.PreferenceFragmentCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.sourcenovel.NovelConfigurableSource
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelSourcePreferencesScreen(val sourceId: Long) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val source = Injekt.get<chimahon.novel.manager.NovelSourceManager>().getNovelSource(sourceId)
        if (source == null) {
            LoadingScreen()
            return
        }
        Scaffold(
            topBar = { AppBar(title = source.name, navigateUp = navigator::pop, scrollBehavior = it) },
        ) { contentPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                NovelSourceDictionaryProfilePicker(
                    sourceId = sourceId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                )
                FragmentContainer(
                    fragmentManager = (context as FragmentActivity).supportFragmentManager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    add(it, NovelSourcePreferencesFragment.getInstance(sourceId), null)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NovelSourceDictionaryProfilePicker(
        sourceId: Long,
        modifier: Modifier = Modifier,
    ) {
        val prefs = remember { Injekt.get<DictionaryPreferences>() }
        val profiles = remember { prefs.profileStore.getProfiles() }
        val overrideKey = remember {
            chimahon.dictionary.DictionaryProfileResolver.sourceOverrideKey(sourceId)
        }

        // Current selection: empty string = Auto
        var selectedId by remember {
            mutableStateOf(prefs.rawProfileOverride(overrideKey).get())
        }
        var expanded by remember { mutableStateOf(false) }

        val resolvedAutoProfile = remember(sourceId) {
            val source = Injekt.get<chimahon.novel.manager.NovelSourceManager>().getOrStub(sourceId)
            prefs.profileResolver.resolve(
                sourceId = 0L,
                sourceLang = source.lang,
            )
        }
        val autoLabel = "Auto (${resolvedAutoProfile.name})"

        // Label shown in the field
        val selectedLabel = if (selectedId.isEmpty()) {
            autoLabel
        } else {
            profiles.firstOrNull { it.id == selectedId }?.name ?: autoLabel
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier,
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(MR.strings.pref_dict_profile_override_source)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                // Auto (clear override)
                DropdownMenuItem(
                    text = { Text(autoLabel) },
                    onClick = {
                        selectedId = ""
                        prefs.rawProfileOverride(overrideKey).delete()
                        expanded = false
                    },
                )
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            selectedId = profile.id
                            prefs.rawProfileOverride(overrideKey).set(profile.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun FragmentContainer(
        fragmentManager: FragmentManager,
        modifier: Modifier = Modifier,
        commit: FragmentTransaction.(containerId: Int) -> Unit,
    ) {
        val containerId by rememberSaveable { mutableIntStateOf(View.generateViewId()) }
        var initialized by rememberSaveable { mutableStateOf(false) }
        AndroidView(
            modifier = modifier,
            factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
            update = { view ->
                if (!initialized) {
                    fragmentManager.commit { commit(view.id) }
                    initialized = true
                } else {
                    fragmentManager.onContainerAvailable(view)
                }
            },
        )
    }

    private fun FragmentManager.onContainerAvailable(view: FragmentContainerView) {
        val method = FragmentManager::class.java.getDeclaredMethod(
            "onContainerAvailable",
            FragmentContainerView::class.java,
        )
        method.isAccessible = true
        method.invoke(this, view)
    }
}

class NovelSourcePreferencesFragment : PreferenceFragmentCompat() {

    override fun getContext(): Context? {
        val superCtx = super.getContext() ?: return null
        val tv = TypedValue()
        superCtx.theme.resolveAttribute(R.attr.preferenceTheme, tv, true)
        return ContextThemeWrapper(superCtx, tv.resourceId)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = populateScreen()
    }

    private fun populateScreen(): androidx.preference.PreferenceScreen {
        val sourceId = requireArguments().getLong(SOURCE_ID)
        val source = Injekt.get<chimahon.novel.manager.NovelSourceManager>().getNovelSource(sourceId) as? NovelConfigurableSource
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        if (source != null) {
            // SimpleLNReaderSource wires its own SharedPreferences via
            // preferenceManager.sharedPreferencesName = "jsplugin_storage_$pluginId"
            source.setupPreferenceScreen(screen)
        }
        return screen
    }

    companion object {
        private const val SOURCE_ID = "source_id"
        fun getInstance(sourceId: Long) = NovelSourcePreferencesFragment().apply {
            arguments = Bundle().apply { putLong(SOURCE_ID, sourceId) }
        }
    }
}

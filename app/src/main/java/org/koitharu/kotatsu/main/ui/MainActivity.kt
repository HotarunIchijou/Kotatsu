package org.koitharu.kotatsu.main.ui

import android.app.ActivityOptions
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.*
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BaseActivity
import org.koitharu.kotatsu.core.model.Manga
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.AppSection
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.databinding.ActivityMainBinding
import org.koitharu.kotatsu.databinding.NavigationHeaderBinding
import org.koitharu.kotatsu.details.ui.DetailsActivity
import org.koitharu.kotatsu.favourites.ui.FavouritesContainerFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.remotelist.ui.RemoteListFragment
import org.koitharu.kotatsu.search.ui.SearchActivity
import org.koitharu.kotatsu.search.ui.global.GlobalSearchActivity
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionFragment
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionViewModel
import org.koitharu.kotatsu.settings.AppUpdateChecker
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.onboard.OnboardDialogFragment
import org.koitharu.kotatsu.suggestions.ui.SuggestionsFragment
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.tracker.ui.FeedFragment
import org.koitharu.kotatsu.tracker.work.TrackWorker
import org.koitharu.kotatsu.utils.ext.getDisplayMessage
import org.koitharu.kotatsu.utils.ext.hideKeyboard
import org.koitharu.kotatsu.utils.ext.measureHeight
import org.koitharu.kotatsu.utils.ext.resolveDp

private const val TAG_PRIMARY = "primary"
private const val TAG_SEARCH = "search"

class MainActivity : BaseActivity<ActivityMainBinding>(),
	NavigationView.OnNavigationItemSelectedListener, AppBarOwner,
	View.OnClickListener, View.OnFocusChangeListener, SearchSuggestionListener {

	private val viewModel by viewModel<MainViewModel>()
	private val searchSuggestionViewModel by viewModel<SearchSuggestionViewModel>()

	private lateinit var navHeaderBinding: NavigationHeaderBinding
	private lateinit var drawerToggle: ActionBarDrawerToggle

	override val appBar: AppBarLayout
		get() = binding.appbar

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityMainBinding.inflate(layoutInflater))
		navHeaderBinding = NavigationHeaderBinding.inflate(layoutInflater)
		drawerToggle = ActionBarDrawerToggle(
			this,
			binding.drawer,
			binding.toolbar,
			R.string.open_menu,
			R.string.close_menu
		)
		drawerToggle.setHomeAsUpIndicator(ContextCompat.getDrawable(this, R.drawable.ic_arrow_back))
		drawerToggle.setToolbarNavigationClickListener {
			binding.searchView.hideKeyboard()
			onBackPressed()
		}
		binding.drawer.addDrawerListener(drawerToggle)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)

		with(binding.searchView) {
			onFocusChangeListener = this@MainActivity
			searchSuggestionListener = this@MainActivity
		}

		with(binding.navigationView) {
			val menuView =
				findViewById<RecyclerView>(com.google.android.material.R.id.design_navigation_view)
			ViewCompat.setOnApplyWindowInsetsListener(navHeaderBinding.root) { v, insets ->
				val systemWindowInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
				v.updatePadding(top = systemWindowInsets.top)
				// NavigationView doesn't dispatch insets to the menu view, so pad the bottom here.
				menuView.updatePadding(bottom = systemWindowInsets.bottom)
				insets
			}
			addHeaderView(navHeaderBinding.root)
			setNavigationItemSelectedListener(this@MainActivity)
		}

		binding.fab.setOnClickListener(this@MainActivity)

		supportFragmentManager.findFragmentByTag(TAG_PRIMARY)?.let {
			if (it is HistoryListFragment) binding.fab.show() else binding.fab.hide()
		} ?: run {
			openDefaultSection()
		}
		if (savedInstanceState == null) {
			onFirstStart()
		}

		viewModel.onOpenReader.observe(this, this::onOpenReader)
		viewModel.onError.observe(this, this::onError)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.remoteSources.observe(this, this::updateSideMenu)
		viewModel.isSuggestionsEnabled.observe(this, this::setSuggestionsEnabled)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		drawerToggle.isDrawerIndicatorEnabled =
			binding.drawer.getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_UNLOCKED
	}

	override fun onPostCreate(savedInstanceState: Bundle?) {
		super.onPostCreate(savedInstanceState)
		drawerToggle.syncState()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		drawerToggle.onConfigurationChanged(newConfig)
	}

	override fun onBackPressed() {
		val fragment = supportFragmentManager.findFragmentByTag(TAG_SEARCH)
		binding.searchView.clearFocus()
		when {
			binding.drawer.isDrawerOpen(binding.navigationView) -> binding.drawer.closeDrawer(
				binding.navigationView)
			fragment != null -> supportFragmentManager.commit {
				remove(fragment)
				setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
				runOnCommit { onSearchClosed() }
			}
			else -> super.onBackPressed()
		}
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return drawerToggle.onOptionsItemSelected(item) || when (item.itemId) {
			else -> super.onOptionsItemSelected(item)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.fab -> viewModel.openLastReader()
		}
	}

	override fun onNavigationItemSelected(item: MenuItem): Boolean {
		if (item.groupId == R.id.group_remote_sources) {
			val source = MangaSource.values().getOrNull(item.itemId) ?: return false
			setPrimaryFragment(RemoteListFragment.newInstance(source))
			searchSuggestionViewModel.onSourceChanged(source)
		} else {
			searchSuggestionViewModel.onSourceChanged(null)
			when (item.itemId) {
				R.id.nav_history -> {
					viewModel.defaultSection = AppSection.HISTORY
					setPrimaryFragment(HistoryListFragment.newInstance())
				}
				R.id.nav_favourites -> {
					viewModel.defaultSection = AppSection.FAVOURITES
					setPrimaryFragment(FavouritesContainerFragment.newInstance())
				}
				R.id.nav_local_storage -> {
					viewModel.defaultSection = AppSection.LOCAL
					setPrimaryFragment(LocalListFragment.newInstance())
				}
				R.id.nav_suggestions -> {
					viewModel.defaultSection = AppSection.SUGGESTIONS
					setPrimaryFragment(SuggestionsFragment.newInstance())
				}
				R.id.nav_feed -> {
					viewModel.defaultSection = AppSection.FEED
					setPrimaryFragment(FeedFragment.newInstance())
				}
				R.id.nav_action_settings -> {
					startActivity(SettingsActivity.newIntent(this))
					return true
				}
				else -> return false
			}
		}
		binding.drawer.closeDrawers()
		return true
	}

	override fun onWindowInsetsChanged(insets: Insets) {
		binding.toolbarCard.updateLayoutParams<MarginLayoutParams> {
			topMargin = insets.top + resources.resolveDp(8)
		}
		binding.fab.updateLayoutParams<MarginLayoutParams> {
			bottomMargin = insets.bottom + topMargin
			leftMargin = insets.left + topMargin
			rightMargin = insets.right + topMargin
		}
		binding.container.updateLayoutParams<MarginLayoutParams> {
			topMargin = -(binding.appbar.measureHeight())
		}
	}

	override fun onFocusChange(v: View?, hasFocus: Boolean) {
		val fragment = supportFragmentManager.findFragmentByTag(TAG_SEARCH)
		if (v?.id == R.id.searchView && hasFocus) {
			if (fragment == null) {
				supportFragmentManager.commit {
					add(R.id.container, SearchSuggestionFragment.newInstance(), TAG_SEARCH)
					setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
					runOnCommit { onSearchOpened() }
				}
			}
		}
	}

	override fun onMangaClick(manga: Manga) {
		startActivity(DetailsActivity.newIntent(this, manga))
	}

	override fun onQueryClick(query: String, submit: Boolean) {
		binding.searchView.query = query
		if (submit) {
			if (query.isNotEmpty()) {
				val source = searchSuggestionViewModel.getLocalSearchSource()
				if (source != null) {
					startActivity(SearchActivity.newIntent(this, source, query))
				} else {
					startActivity(GlobalSearchActivity.newIntent(this, query))
				}
				searchSuggestionViewModel.saveQuery(query)
			}
		}
	}

	override fun onQueryChanged(query: String) {
		searchSuggestionViewModel.onQueryChanged(query)
	}

	override fun onClearSearchHistory() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.clear_search_history)
			.setMessage(R.string.text_clear_search_history_prompt)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ ->
				searchSuggestionViewModel.clearSearchHistory()
			}.show()
	}

	private fun onOpenReader(manga: Manga) {
		val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			ActivityOptions.makeClipRevealAnimation(
				binding.fab, 0, 0, binding.fab.measuredWidth, binding.fab.measuredHeight
			)
		} else {
			ActivityOptions.makeScaleUpAnimation(
				binding.fab, 0, 0, binding.fab.measuredWidth, binding.fab.measuredHeight
			)
		}
		startActivity(ReaderActivity.newIntent(this, manga, null), options?.toBundle())
	}

	private fun onError(e: Throwable) {
		Snackbar.make(binding.container, e.getDisplayMessage(resources), Snackbar.LENGTH_SHORT)
			.show()
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		binding.fab.isEnabled = !isLoading
		if (isLoading) {
			binding.fab.setImageDrawable(CircularProgressDrawable(this).also {
				it.setColorSchemeColors(R.color.kotatsu_onPrimaryContainer)
				it.strokeWidth = resources.resolveDp(3.5f)
				it.start()
			})
		} else {
			binding.fab.setImageResource(R.drawable.ic_read_fill)
		}
	}

	private fun updateSideMenu(remoteSources: List<MangaSource>) {
		val submenu = binding.navigationView.menu.findItem(R.id.nav_remote_sources).subMenu
		submenu.removeGroup(R.id.group_remote_sources)
		remoteSources.forEachIndexed { index, source ->
			submenu.add(R.id.group_remote_sources, source.ordinal, index, source.title)
				.setIcon(R.drawable.ic_manga_source)
		}
		submenu.setGroupCheckable(R.id.group_remote_sources, true, true)
	}

	private fun setSuggestionsEnabled(isEnabled: Boolean) {
		val item = binding.navigationView.menu.findItem(R.id.nav_suggestions) ?: return
		if (!isEnabled && item.isChecked) {
			binding.navigationView.setCheckedItem(R.id.nav_history)
		}
		item.isVisible = isEnabled
	}

	private fun openDefaultSection() {
		when (viewModel.defaultSection) {
			AppSection.LOCAL -> {
				binding.navigationView.setCheckedItem(R.id.nav_local_storage)
				setPrimaryFragment(LocalListFragment.newInstance())
			}
			AppSection.FAVOURITES -> {
				binding.navigationView.setCheckedItem(R.id.nav_favourites)
				setPrimaryFragment(FavouritesContainerFragment.newInstance())
			}
			AppSection.HISTORY -> {
				binding.navigationView.setCheckedItem(R.id.nav_history)
				setPrimaryFragment(HistoryListFragment.newInstance())
			}
			AppSection.FEED -> {
				binding.navigationView.setCheckedItem(R.id.nav_feed)
				setPrimaryFragment(FeedFragment.newInstance())
			}
			AppSection.SUGGESTIONS -> {
				binding.navigationView.setCheckedItem(R.id.nav_suggestions)
				setPrimaryFragment(SuggestionsFragment.newInstance())
			}
		}
	}

	private fun setPrimaryFragment(fragment: Fragment) {
		supportFragmentManager.beginTransaction()
			.replace(R.id.container, fragment, TAG_PRIMARY)
			.commit()
		if (fragment is HistoryListFragment) binding.fab.show() else binding.fab.hide()
	}

	private fun onSearchOpened() {
		binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
		drawerToggle.isDrawerIndicatorEnabled = false
	}

	private fun onSearchClosed() {
		binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
		drawerToggle.isDrawerIndicatorEnabled = true
	}

	private fun onFirstStart() {
		lifecycleScope.launch(Dispatchers.Default) {
			TrackWorker.setup(applicationContext)
			SuggestionsWorker.setup(applicationContext)
			AppUpdateChecker(this@MainActivity).checkIfNeeded()
			if (!get<AppSettings>().isSourcesSelected) {
				withContext(Dispatchers.Main) {
					OnboardDialogFragment.showWelcome(supportFragmentManager)
				}
			}
		}
	}
}
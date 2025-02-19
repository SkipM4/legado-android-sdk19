package io.legado.app.ui.book.searchContent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import com.github.liuyueyi.quick.transfer.ChineseUtils
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivitySearchContentBinding
import io.legado.app.help.AppConfig
import io.legado.app.help.BookHelp
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SearchContentActivity :
    VMBaseActivity<ActivitySearchContentBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    override val binding by viewBinding(ActivitySearchContentBinding::inflate)
    override val viewModel by viewModels<SearchContentViewModel>()
    private val adapter by lazy { SearchContentAdapter(this, this) }
    private val mLayoutManager by lazy { UpLinearLayoutManager(this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var searchResultCounts = 0
    private var durChapterIndex = 0
    private var searchResultList: MutableList<SearchResult> = mutableListOf()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bbg = bottomBackground
        val btc = getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        binding.llSearchBaseInfo.setBackgroundColor(bbg)
        binding.tvCurrentSearchInfo.setTextColor(btc)
        binding.ivSearchContentTop.setColorFilter(btc)
        binding.ivSearchContentBottom.setColorFilter(btc)
        initSearchView()
        initRecyclerView()
        initView()
        intent.getStringExtra("bookUrl")?.let {
            viewModel.initBook(it) {
                initBook()
            }
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.onActionViewExpanded()
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        searchView.clearFocus()
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (viewModel.lastQuery != query) {
                    startContentSearch(query)
                }
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() {
        binding.ivSearchContentTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(
                0,
                0
            )
        }
        binding.ivSearchContentBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initBook() {
        binding.tvCurrentSearchInfo.text = "搜索结果：$searchResultCounts"
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                searchView.setQuery(searchWord, true)
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        launch(Dispatchers.IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Dispatchers.Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<BookChapter>(EventBus.SAVE_CONTENT) { chapter ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (chapter.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    adapter.notifyItemChanged(chapter.index, true)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun startContentSearch(newText: String) {
        // 按章节搜索内容
        if (newText.isNotBlank()) {
            adapter.clearItems()
            searchResultList.clear()
            binding.refreshProgressBar.isAutoLoading = true
            searchResultCounts = 0
            viewModel.lastQuery = newText
            var searchResults = listOf<SearchResult>()
            launch(Dispatchers.Main) {
                appDb.bookChapterDao.getChapterList(viewModel.bookUrl).map { chapter ->
                    withContext(Dispatchers.IO) {
                        if (isLocalBook
                            || adapter.cacheFileNames.contains(chapter.getFileName())
                        ) {
                            searchResults = searchChapter(newText, chapter)
                        }
                    }
                    if (searchResults.isNotEmpty()) {
                        searchResultList.addAll(searchResults)
                        binding.refreshProgressBar.isAutoLoading = false
                        binding.tvCurrentSearchInfo.text = "搜索结果：$searchResultCounts"
                        adapter.addItems(searchResults)
                        searchResults = listOf()
                    }
                }
            }
        }
    }

    private suspend fun searchChapter(query: String, chapter: BookChapter?): List<SearchResult> {
        val searchResults: MutableList<SearchResult> = mutableListOf()
        var positions: List<Int>
        var replaceContents: List<String>?
        var totalContents: String
        if (chapter != null) {
            viewModel.book?.let { book ->
                val bookContent = BookHelp.getContent(book, chapter)
                if (bookContent != null) {
                    //搜索替换后的正文
                    withContext(Dispatchers.IO) {
                        chapter.title = when (AppConfig.chineseConverterType) {
                            1 -> ChineseUtils.t2s(chapter.title)
                            2 -> ChineseUtils.s2t(chapter.title)
                            else -> chapter.title
                        }
                        replaceContents =
                            viewModel.contentProcessor!!.getContent(
                                book,
                                chapter,
                                bookContent,
                                chineseConvert = false,
                                reSegment = false
                            )
                    }
                    totalContents = replaceContents?.joinToString("") ?: bookContent
                    positions = searchPosition(totalContents, query)
                    var count = 1
                    positions.map {
                        val construct = constructText(totalContents, it, query)
                        val result = SearchResult(
                            index = searchResultCounts,
                            indexWithinChapter = count,
                            text = construct[1] as String,
                            chapterTitle = chapter.title,
                            query = query,
                            chapterIndex = chapter.index,
                            newPosition = construct[0] as Int,
                            contentPosition = it
                        )
                        count += 1
                        searchResultCounts += 1
                        searchResults.add(result)
                    }
                }
            }
        }
        return searchResults
    }

    private fun searchPosition(content: String, pattern: String): List<Int> {
        val position: MutableList<Int> = mutableListOf()
        var index = content.indexOf(pattern)
        while (index >= 0) {
            position.add(index)
            index = content.indexOf(pattern, index + 1)
        }
        return position
    }

    private fun constructText(content: String, position: Int, query: String): Array<Any> {
        // 构建关键词周边文字，在搜索结果里显示
        // todo: 判断段落，只在关键词所在段落内分割
        // todo: 利用标点符号分割完整的句
        // todo: length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = position - length
        var po2 = position + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val newPosition = position - po1
        val newText = content.substring(po1, po2)
        return arrayOf(newPosition, newText)
    }

    val isLocalBook: Boolean
        get() = viewModel.book?.isLocalBook() == true

    override fun openSearchResult(searchResult: SearchResult) {
        val searchData = Intent()
        searchData.putExtra("index", searchResult.chapterIndex)
        searchData.putExtra("contentPosition", searchResult.contentPosition)
        searchData.putExtra("query", searchResult.query)
        searchData.putExtra("indexWithinChapter", searchResult.indexWithinChapter)
        setResult(RESULT_OK, searchData)
        finish()
    }

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

}
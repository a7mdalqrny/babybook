package com.serwylo.babybook.bookviewer

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.serwylo.babybook.R
import com.serwylo.babybook.book.BookConfig
import com.serwylo.babybook.book.Page
import com.serwylo.babybook.databinding.ActivityBookViewerBinding
import com.serwylo.babybook.db.AppDatabase
import com.serwylo.babybook.db.entities.BookPage
import com.serwylo.babybook.db.repositories.BookRepository
import com.serwylo.babybook.pdf.generatePdf
import com.serwylo.babybook.utils.viewInWikipedia
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

class BookViewerActivity : AppCompatActivity() {

    private lateinit var viewModel: BookViewerViewModel
    private lateinit var binding: ActivityBookViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding = ActivityBookViewerBinding.inflate(layoutInflater)

        setContentView(binding.root)

        val dao = AppDatabase.getInstance(this).bookDao()
        val bookId = intent.extras?.getLong(EXTRA_BOOK_ID) ?: error("Can't view book, expected to find the ID of the book in the Intent $EXTRA_BOOK_ID but not found.")

        viewModel = ViewModelProvider(this@BookViewerActivity, BookViewerModelFactory(BookRepository(dao), bookId)).get(BookViewerViewModel::class.java)
        setup(binding)

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.view_book_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            /*R.id.share -> {
                share()
                return true
            }*/
            R.id.view_in_wikipedia -> {
                viewModel.currentPage()?.wikiPageTitle?.also { title ->
                    viewInWikipedia(this, title)
                }
                return true
            }
            R.id.pdf -> {
                startPdfExport()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun share() {

    }

    private fun startPdfExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "${viewModel.bookWithPages.value?.book?.title}.pdf")
        }
        startActivityForResult(intent, RESULT_CREATE_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode == RESULT_CREATE_FILE && resultCode == RESULT_OK) {
            val uri = resultData?.data ?: return
            try {
                lifecycleScope.launchWhenCreated {
                    withContext(Dispatchers.IO) {
                        contentResolver.openFileDescriptor(uri, "w")?.use {
                            FileOutputStream(it.fileDescriptor).use { outputStream ->
                                exportPdf(outputStream)
                            }
                        }
                    }

                    Toast.makeText(this@BookViewerActivity, "PDF successfully created.", Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this, "Uh oh. Something went wrong making your PDF. It may not have saved correctly.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun exportPdf(outputStream: OutputStream) {
        viewModel.bookWithPages.value?.also { bookWithPages ->
            val pages = bookWithPages.pages.map {
                val file = it.imageFile(this)

                Page(
                    it.title(),
                    file,
                    it.text() ?: "",
                )
            }

            val pdfFile = File(cacheDir, "${bookWithPages.book.title}.pdf")

            generatePdf(bookWithPages.book.title, pages.filterNotNull(), pdfFile, BookConfig.Default)

            pdfFile.inputStream().use { it.copyTo(outputStream) }
        }
    }

    private fun setup(binding: ActivityBookViewerBinding) {

        viewModel.bookWithPages.observe(this) { value ->
            supportActionBar?.title = value.book.title

            val firstPage = value.pages.firstOrNull()
            if (firstPage == null) {
                Toast.makeText(this, "This book does not have any pages yet.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                showPage(firstPage)
            }
        }

        binding.previous.setOnClickListener { viewModel.turnToPreviousPage() }
        binding.next.setOnClickListener { viewModel.turnToNextPage() }

        viewModel.currentPageIndex.observe(this) { currentPage ->
            // Be defensive here, because it is almost always guaranteed that we receive a
            // currentPageIndex value of 0 in this observer, prior to loading the page data from
            // the database. If so, just ignore it (the actual first page render will be triggered
            // by the observer on viewModel.bookWithPages).
            val pages = viewModel.bookWithPages.value?.pages
            if (pages != null && pages.size > currentPage) {
                showPage(pages[currentPage])
            }
        }
    }

    private fun showPage(page: BookPage) {

        binding.previous.visibility = if (viewModel.hasPreviousPage()) View.VISIBLE else View.GONE
        binding.next.visibility = if (viewModel.hasNextPage()) View.VISIBLE else View.GONE

        if (page.imagePath == null) {
            binding.image.visibility = View.GONE
        } else {
            binding.image.visibility = View.VISIBLE
            Picasso.get()
                .load(page.imagePath)
                .fit()
                .centerCrop()
                .into(binding.image)
        }

        binding.title.text = page.title()
        binding.text.text = page.text()
    }

    companion object {

        private const val TAG = "BookViewerActivity"

        const val EXTRA_BOOK_ID = "bookId"

        private const val RESULT_CREATE_FILE = 1

    }

}
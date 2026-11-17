package com.albayda.court

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.data.AuthManager
import com.albayda.court.data.CourtPost
import com.albayda.court.data.SyncManager
import com.albayda.court.databinding.ActivityPostsBinding
import com.albayda.court.databinding.ItemPostBinding
import java.io.ByteArrayOutputStream
import java.io.File

class PostsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPostsBinding
    private var isAdmin = false
    private var pickedBytes: ByteArray? = null
    private val pickImageRequest = 1201

    private val syncListener: () -> Unit = {
        runOnUiThread {
            renderPosts()
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthManager.init(this)
        SyncManager.init(this)
        isAdmin = AuthManager.isPrimaryAdmin(AuthManager.getSession())

        binding.adminPostPanel.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnBackPosts.setOnClickListener { finish() }
        binding.btnRefreshPosts.setOnClickListener {
            binding.pbPosts.visibility = View.VISIBLE
            SyncManager.refresh()
            binding.pbPosts.postDelayed({ binding.pbPosts.visibility = View.GONE }, 1500)
        }

        binding.ivPickPostImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            startActivityForResult(Intent.createChooser(intent, "اختر صورة المنشور"), pickImageRequest)
        }

        binding.btnPublishPost.setOnClickListener {
            val details = binding.etPostDetails.text.toString().trim()
            if (pickedBytes == null) {
                Toast.makeText(this, "يرجى اختيار صورة المنشور", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (details.isBlank()) {
                Toast.makeText(this, "يرجى كتابة تفاصيل المنشور", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val author = AuthManager.getSession()?.username?.ifBlank { "مدير النظام" } ?: "مدير النظام"
            SyncManager.addPost(author, details, pickedBytes)
            pickedBytes = null
            binding.etPostDetails.text?.clear()
            binding.ivPickPostImage.setImageResource(android.R.drawable.ic_menu_camera)
            Toast.makeText(this, "تم نشر المنشور ومزامنته", Toast.LENGTH_SHORT).show()
            renderPosts()
        }

        renderPosts()
        updateStatus()
        SyncManager.refresh()
    }

    override fun onStart() {
        super.onStart()
        SyncManager.addListener(syncListener)
    }

    override fun onStop() {
        SyncManager.removeListener(syncListener)
        super.onStop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickImageRequest && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val bytes = compressImage(uri)
            if (bytes == null) {
                Toast.makeText(this, "تعذر قراءة الصورة", Toast.LENGTH_SHORT).show()
                return
            }
            pickedBytes = bytes
            binding.ivPickPostImage.setImageURI(uri)
        }
    }

    private fun compressImage(uri: Uri): ByteArray? {
        return try {
            val stream = contentResolver.openInputStream(uri) ?: return null
            val original = stream.use { BitmapFactory.decodeStream(it) } ?: return null
            val maxSide = 1400f
            val scale = minOf(1f, maxSide / maxOf(original.width, original.height).toFloat())
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt(),
                    (original.height * scale).toInt(),
                    true
                )
            } else original
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun updateStatus() {
        binding.tvSyncStatus.text = if (SyncManager.configured())
            "المزامنة مفعّلة — المنشورات تُحدَّث تلقائياً لدى جميع المستخدمين"
        else
            "المزامنة غير مفعّلة — افتح لوحة الإدارة واضبط رابط خادم المزامنة"
    }

    private fun renderPosts() {
        val posts = SyncManager.getPosts()
        binding.tvEmptyPosts.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
        binding.postsContainer.removeAllViews()
        posts.forEach { post ->
            val item = ItemPostBinding.inflate(layoutInflater, binding.postsContainer, false)
            val path = post.imagePath
            if (path.isNotBlank()) {
                runCatching {
                    val file = File(path)
                    if (file.exists()) item.ivPostImage.setImageURI(Uri.fromFile(file))
                }
            }
            item.tvPostDetails.text = post.details
            item.tvPostTime.text = "وقت النشر: ${post.timestamp.replace('T', ' ')}"
            item.btnDownload.setOnClickListener { downloadPost(post) }
            item.btnDelete.visibility = if (isAdmin) View.VISIBLE else View.GONE
            item.btnDelete.setOnClickListener {
                SyncManager.deletePost(post.id)
                Toast.makeText(this, "تم حذف المنشور", Toast.LENGTH_SHORT).show()
                renderPosts()
            }
            binding.postsContainer.addView(item.root)
        }
    }

    private fun downloadPost(post: CourtPost) {
        val path = post.imagePath
        val source = if (path.isNotBlank()) File(path) else null
        if (source == null || !source.exists()) {
            Toast.makeText(this, "صورة المنشور غير متوفرة على هذا الجهاز", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "منشور_محكمة_البيضاء_${post.timestamp.take(10)}.jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { input -> input.copyTo(out) }
                    }
                }
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "posts").apply { mkdirs() }
                source.copyTo(File(dir, name), overwrite = true)
            }
            Toast.makeText(this, "تم تحميل الصورة بنجاح", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر تحميل الصورة", Toast.LENGTH_SHORT).show()
        }
    }
}

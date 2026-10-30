package com.edgepulse.monitor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgepulse.core.cloud.*
import com.edgepulse.core.vision.ItemProfile
import com.edgepulse.core.vision.ReferenceMatcher
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.security.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

data class ItemUiState(val profiles: List<ItemProfile> = emptyList(), val selectedId: String = "", val usage: List<ApiUsage> = emptyList(),
    val photo: Bitmap? = null, val approved: Bitmap? = null, val name: String = "", val traits: String = "",
    val setup: ProviderSetup? = null, val photoConsentContext: String? = null, val monitoring: Boolean = false,
    val referenceUsable: Boolean = false,
    val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class ItemViewModel @Inject constructor(@ApplicationContext private val context: Context, private val settings: SecureSettingsStore,
    private val references: ReferenceImageStore, private val credentials: ApiKeyRepository, private val cloud: CloudAnalysisService,
    private val interlock: MonitoringNetworkInterlock) : ViewModel() {
    private val mutable = MutableStateFlow(ItemUiState())
    val state = mutable.asStateFlow()
    private var jpeg: ByteArray? = null
    private var operation: Job? = null
    init { viewModelScope.launch {
        settings.dataStore.data.collect { saved ->
            val connection = saved.credentials.firstOrNull { it.validated && it.key.isNotEmpty() && it.setup.provider == saved.activeCloudProvider }
                ?: saved.credentials.firstOrNull { it.validated && it.key.isNotEmpty() }
            val setup = connection?.setup?.takeIf { it.model.isNotBlank() && ProviderDefaults.isOfficialOrigin(it.provider, it.validationEndpoint) }
            mutable.update { it.copy(profiles = saved.itemProfiles, selectedId = saved.selectedItemId,
                usage = Json.decodeFromString(saved.apiUsageJson), monitoring = saved.activeSessionId != null, setup = setup,
                photoConsentContext = if (setup != null && jpeg != null) CloudAnalysisService.photoConsentContext(setup, jpeg!!) else null) }
        }
    } }
    fun name(value: String) { mutable.update { it.copy(name = value.filterNot(Char::isISOControl).take(80)) } }
    fun traits(value: String) { mutable.update { it.copy(traits = value.take(4000)) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun showMessage(value: String) { mutable.update { it.copy(message = value) } }
    fun cancel() { operation?.cancel() }
    fun discardPhoto() {
        if (state.value.busy) return
        clearPhoto()
        mutable.update { it.copy(photo = null, approved = null, referenceUsable = false, photoConsentContext = null, name = "", traits = "", message = null) }
    }

    fun acceptCameraPhoto(bitmap: Bitmap?) {
        if (bitmap == null) return
        if (state.value.monitoring || state.value.busy) { bitmap.recycle(); return }
        clearPhoto()
        val photo = bounded(bitmap)
        mutable.update { it.copy(photo = photo, approved = null, name = "", traits = "", photoConsentContext = null) }
    }
    fun loadPhoto(uri: Uri) = action {
        interlock.withSessionState {
        check(settings.dataStore.data.first().activeSessionId == null)
        val bitmap = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth in 1..40_000 && options.outHeight in 1..40_000)
            var sample = 1; while (options.outWidth / sample > 1280 || options.outHeight / sample > 1280) sample *= 2
            options.inJustDecodeBounds = false; options.inSampleSize = sample
            context.contentResolver.openInputStream(uri).use { checkNotNull(BitmapFactory.decodeStream(it, null, options)) }
        }
        clearPhoto()
        val photo = withContext(Dispatchers.Default) {
            val orientation = try { context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) ExifInterface.ORIENTATION_NORMAL else ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f,1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            }
            val upright = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also { bitmap.recycle() }
            bounded(upright)
        }
        mutable.update { it.copy(photo = photo, approved = null, name = "", traits = "", photoConsentContext = null) }
        }
    }
    fun approvePhoto(bitmap: Bitmap) = action {
        check(!state.value.monitoring)
        val approved = withContext(Dispatchers.Default) { FacePrivacyBlur.redact(bounded(bitmap)) }
        val usable = withContext(Dispatchers.Default) {
            val sample = FloatArray(8*8*3)
            for (y in 0..7) for (x in 0..7) {
                val color = approved.getPixel(((x+.5f)*approved.width/8).toInt(),((y+.5f)*approved.height/8).toInt())
                val i = (y*8+x)*3; sample[i] = (color and 255).toFloat(); sample[i+1] = (color shr 8 and 255).toFloat(); sample[i+2] = (color shr 16 and 255).toFloat()
            }
            ReferenceMatcher(sample,8,8).usable.also { sample.fill(0f) }
        }
        jpeg?.fill(0)
        jpeg = withContext(Dispatchers.Default) { ByteArrayOutputStream().use { check(approved.compress(Bitmap.CompressFormat.JPEG, 85, it)); it.toByteArray() } }
        val setup = state.value.setup
        mutable.update { it.copy(approved = approved, referenceUsable = usable, photoConsentContext = setup?.let { s -> CloudAnalysisService.photoConsentContext(s, jpeg!!) },
            message = if (usable) "Review the cropped image. Face detection is best-effort; mask any remaining private details before uploading."
                else "This crop has too little distinctive texture for reference matching. Include the object's outline or markings, or discard it and choose a trained category.") }
    }
    fun editPhoto() {
        if (state.value.busy) return
        jpeg?.fill(0); jpeg = null
        mutable.update { it.copy(approved = null, photoConsentContext = null) }
    }
    fun confirmUpload(consent: OneShotUploadConsent) = action {
        check(!state.value.monitoring)
        val setup = checkNotNull(state.value.setup)
        val image = checkNotNull(jpeg).copyOf()
        try {
            val result = credentials.withApiKey(setup.provider) { key -> cloud.describeItem(setup, key, image, consent) }
            mutable.update { it.copy(name = result.name, traits = result.characteristics, message = "AI description received. Review the name and traits before saving. This does not train YOLOX.") }
        } finally { image.fill(0) }
    }
    fun savePhotoTarget(threshold: Float = .90f) = action {
        val snapshot = state.value
        val image = checkNotNull(snapshot.approved)
        require(snapshot.name.isNotBlank() && snapshot.referenceUsable)
        interlock.withSessionState {
            check(settings.dataStore.data.first().activeSessionId == null)
            check(settings.dataStore.data.first().itemProfiles.size < 20)
            val filename = references.save(image)
            try {
                val profile = ItemProfile(UUID.randomUUID().toString(), snapshot.name, snapshot.traits, filename, similarityThreshold = threshold)
                profile.validate()
                settings.dataStore.updateData { it.copy(itemProfiles = it.itemProfiles + profile, selectedItemId = profile.id) }
            } catch (failure: Exception) { references.remove(filename); throw failure }
        }
        clearPhoto()
        mutable.update { it.copy(photo = null, approved = null, photoConsentContext = null, name = "", traits = "", message = "Item saved. Live matching stays on this phone; validate it against real examples.") }
    }
    fun select(id: String) = action {
        interlock.withSessionState { settings.dataStore.updateData {
            check(it.activeSessionId == null); require(id.isEmpty() || it.itemProfiles.any { p -> p.id == id })
            it.copy(selectedItemId = id)
        } }
    }
    fun selectClass(id: Int, label: String) = action {
        interlock.withSessionState { settings.dataStore.updateData {
            check(it.activeSessionId == null)
            val existing = it.itemProfiles.firstOrNull { p -> p.classId == id }
            val profile = existing ?: ItemProfile(UUID.randomUUID().toString(), label, classId = id).also(ItemProfile::validate)
            check(existing != null || it.itemProfiles.size < 20)
            it.copy(itemProfiles = if (existing == null) it.itemProfiles + profile else it.itemProfiles, selectedItemId = profile.id)
        } }
    }
    fun remove(id: String) = action {
        interlock.withSessionState {
            var file = ""
            settings.dataStore.updateData {
                check(it.activeSessionId == null)
                file = it.itemProfiles.single { p -> p.id == id }.referenceFilename
                it.copy(itemProfiles = it.itemProfiles.filterNot { p -> p.id == id }, selectedItemId = if (it.selectedItemId == id) "" else it.selectedItemId)
            }
            if (file.isNotEmpty()) references.remove(file)
        }
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { mutable.update { it.copy(message = "Cancelled.") }; throw cancelled }
            catch (failure: CatalogException) { mutable.update { it.copy(message = when (failure.status) {
                KeyValidationStatus.QUOTA_OR_RATE_LIMIT -> "Provider quota or rate limit reached. Review credits and retry explicitly."
                KeyValidationStatus.PERMISSION_DENIED, KeyValidationStatus.CONFIGURATION_ERROR -> "This model may not accept image input. Choose an image-capable model in Settings."
                KeyValidationStatus.INVALID_KEY -> "The saved key was rejected. Reconnect it in Settings."
                else -> "Photo analysis did not complete. Check the model and connection. The API usage page records the request outcome."
            }) } }
            catch (_: Exception) { mutable.update { it.copy(message = "Could not finish. Stop monitoring, review the photo/name, and check local storage or the AI connection.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun bounded(bitmap: Bitmap): Bitmap {
        val scale = minOf(1f, 640f / maxOf(bitmap.width, bitmap.height))
        return if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width*scale).toInt().coerceAtLeast(1),
            (bitmap.height*scale).toInt().coerceAtLeast(1), true).also { bitmap.recycle() }
    }
    // Displayed Bitmaps are released with Compose/GC; recycling while RenderThread still draws them is unsafe.
    private fun clearPhoto() { jpeg?.fill(0); jpeg = null }
    override fun onCleared() { clearPhoto() }
}

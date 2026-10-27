package com.example.veganscanner

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// --- МОДЕЛИ И СТАТУСЫ ---
enum class Status { VEGAN, VEGETARIAN, NON_VEGETARIAN, DOUBTFUL }
data class Ingredient(val name: String, val status: Status, val description: String)
data class DetectedIngredient(val originalWord: String, val ingredient: Ingredient)
data class AnalysisResult(val overallStatus: Status, val detectedItems: List<DetectedIngredient>)

// --- БАЗА ДАННЫХ ИНГРЕДИЕНТОВ ---
object IngredientsDatabase {
    val items = listOf(
        Ingredient("желатин", Status.NON_VEGETARIAN, "Из костей и кожи животных."),
        Ingredient("кармин", Status.NON_VEGETARIAN, "Краситель из насекомых."),
        Ingredient("сычуг", Status.NON_VEGETARIAN, "Фермент из желудка телят."),
        Ingredient("коллаген", Status.NON_VEGETARIAN, "Животный белок."),
        Ingredient("e120", Status.NON_VEGETARIAN, "Кармин (краситель из насекомых)."),
        Ingredient("e904", Status.NON_VEGETARIAN, "Шеллак (смола насекомых)."),
        Ingredient("молоко", Status.VEGETARIAN, "Продукт животноводства (не веган)."),
        Ingredient("сыворотка", Status.VEGETARIAN, "Молочный продукт."),
        Ingredient("казеин", Status.VEGETARIAN, "Молочный белок."),
        Ingredient("яйцо", Status.VEGETARIAN, "Продукт птицеводства."),
        Ingredient("мед", Status.VEGETARIAN, "Продукт пчеловодства."),
        Ingredient("лактоза", Status.VEGETARIAN, "Молочный сахар."),
        Ingredient("e471", Status.DOUBTFUL, "Моно- и диглицериды. Бывают растительными или животными."),
        Ingredient("глицерин", Status.DOUBTFUL, "Бывает растительным или животным.")
    )
}

// --- АНАЛИЗАТОР СОСТАВА ---
class IngredientsAnalyzer {
    fun analyze(rawText: String): AnalysisResult {
        val tokens = rawText.lowercase()
            .replace("(", ", ").replace(")", ", ")
            .split(Regex("[,;\\n•.]"))
            .map { it.trim() }

        val detected = mutableListOf<DetectedIngredient>()
        for (token in tokens) {
            for (dbItem in IngredientsDatabase.items) {
                if (token.contains(dbItem.name)) {
                    detected.add(DetectedIngredient(token, dbItem))
                    break
                }
            }
        }
        val overall = when {
            detected.any { it.ingredient.status == Status.NON_VEGETARIAN } -> Status.NON_VEGETARIAN
            detected.any { it.ingredient.status == Status.VEGETARIAN } -> Status.VEGETARIAN
            detected.any { it.ingredient.status == Status.DOUBTFUL } -> Status.DOUBTFUL
            else -> Status.VEGAN
        }
        return AnalysisResult(overall, detected.distinctBy { it.ingredient.name })
    }
}

// --- VIEWMODEL ---
sealed interface ScanUiState {
    object Idle : ScanUiState
    object Processing : ScanUiState
    data class Success(val result: AnalysisResult) : ScanUiState
    data class Error(val msg: String) : ScanUiState
}

class ScannerViewModel : ViewModel() {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analyzer = IngredientsAnalyzer()
    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun processImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Processing
            try {
                val text = suspendCancellableCoroutine<String> { cont ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { cont.resume(it.text) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                if (text.isBlank()) {
                    _uiState.value = ScanUiState.Error("Текст не найден. Поднесите камеру ближе.")
                } else {
                    _uiState.value = ScanUiState.Success(analyzer.analyze(text))
                }
            } catch (e: Exception) {
                _uiState.value = ScanUiState.Error("Ошибка: ${e.localizedMessage}")
            }
        }
    }
    fun reset() { _uiState.value = ScanUiState.Idle }
}

// --- ИНТЕРФЕЙС ACTIVITY ---
class MainActivity : ComponentActivity() {
    private val vm: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScannerScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(vm: ScannerViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by vm.uiState.collectAsState()
    var hasPermission by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val preview = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val previewCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewCase, imageCapture)
                    }, ContextCompat.getMainExecutor(ctx))
                    preview
                }
            )

            // Рамка сканирования
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f).fillMaxHeight(0.35f)
                    .align(Alignment.Center)
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
            )

            // Кнопка затвора
            Button(
                onClick = {
                    imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            val bitmap = imageProxy.toBitmap()
                            val degrees = imageProxy.imageInfo.rotationDegrees
                            val rotated = if (degrees != 0) {
                                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            } else bitmap
                            imageProxy.close()
                            vm.processImage(rotated)
                        }
                    })
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)
            ) {
                Text("Сканировать состав", fontSize = 18.sp)
            }
        }

        if (uiState is ScanUiState.Processing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        if (uiState is ScanUiState.Success) {
            val res = (uiState as ScanUiState.Success).result
            ModalBottomSheet(onDismissRequest = { vm.reset() }) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (color, title) = when (res.overallStatus) {
                        Status.VEGAN -> Color(0xFF2E7D32) to "Подходит для веганов 🌱"
                        Status.VEGETARIAN -> Color(0xFFF9A825) to "Подходит вегетарианцам 🧀"
                        Status.NON_VEGETARIAN -> Color(0xFFC62828) to "Не подходит 🚫"
                        Status.DOUBTFUL -> Color(0xFFEF6C00) to "Требует уточнения ❓"
                    }
                    Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(res.detectedItems) {
                            Text("• ${it.ingredient.name.uppercase()}: ${it.ingredient.description}", fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    Button(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text("Закрыть")
                    }
                }
            }
        }

        if (uiState is ScanUiState.Error) {
            AlertDialog(
                onDismissRequest = { vm.reset() },
                confirmButton = { TextButton(onClick = { vm.reset() }) { Text("Ок") } },
                text = { Text((uiState as ScanUiState.Error).msg) }
            )
        }
    }
}

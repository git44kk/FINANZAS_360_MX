package mx.finanzas360.app

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ReminderScheduler.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 360)
        unlocked = !AppLockSettings.isEnabled(this)
        setContent {
            FinanceTheme {
                Box(Modifier.fillMaxSize()) {
                    FinanceApp(onLockChanged = { enabled ->
                        if (enabled) authenticate() else unlocked = true
                    })
                    if (!unlocked) LockScreen(onUnlock = { authenticate() })
                }
            }
        }
        if (AppLockSettings.isEnabled(this)) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (AppLockSettings.isEnabled(this)) unlocked = false
    }

    private fun authenticate() {
        val manager = BiometricManager.from(this)
        val authenticators = if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Configura biometría fuerte o bloqueo seguro de pantalla en Android para usar el bloqueo.", Toast.LENGTH_LONG).show()
            AppLockSettings.setEnabled(this, false)
            unlocked = true
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) unlocked = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear FINANZAS 360 MX")
            .setSubtitle("Confirma tu identidad para acceder a tus finanzas")
            .setAllowedAuthenticators(authenticators)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancelar") }
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable { }.padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(18.dp))
        Text("FINANZAS 360 MX", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Tus datos financieros están protegidos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Desbloquear") }
    }
}

private enum class AppTab(val title: String) {
    HOME("Inicio"), ACCOUNTS("Cuentas"), MOVEMENTS("Movimientos"), BUDGETS("Presupuesto"), MORE("Más")
}

@Composable
private fun FinanceTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFB4A7FF),
        onPrimary = Color(0xFF1B1740),
        secondary = Color(0xFF70D7B2),
        background = Color(0xFF101426),
        surface = Color(0xFF191F35),
        surfaceVariant = Color(0xFF252D47),
        onBackground = Color(0xFFF2F3FA),
        onSurface = Color(0xFFF2F3FA),
        onSurfaceVariant = Color(0xFFB8BED3),
        error = Color(0xFFFF7D86)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun FinanceApp(onLockChanged: (Boolean) -> Unit) {
    val appContext = LocalContext.current.applicationContext
    var database by remember { mutableStateOf<FinanceDb?>(null) }
    var databaseError by remember { mutableStateOf<String?>(null) }
    var initializationAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(appContext, initializationAttempt) {
        databaseError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val opened = FinanceDb(appContext)
                try {
                    // Force SQLCipher/key-store initialization on a worker thread.
                    opened.writableDatabase
                    opened
                } catch (error: Throwable) {
                    runCatching { opened.close() }
                    throw error
                }
            }
        }
        result.onSuccess { database = it }
            .onFailure { error ->
                android.util.Log.e("Finanzas360Startup", "No se pudo inicializar la base de datos", error)
                databaseError = "${error.javaClass.simpleName}: ${error.message ?: "sin detalle"}"
            }
    }

    val readyDatabase = database
    when {
        readyDatabase != null -> FinanceAppContent(readyDatabase, onLockChanged)
        databaseError != null -> DatabaseStartupErrorScreen(databaseError!!) {
            database = null
            databaseError = null
            initializationAttempt++
        }
        else -> StartupLoadingScreen()
    }
}

@Composable
private fun StartupLoadingScreen() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101426)).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null,
            tint = Color(0xFFB4A7FF), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(18.dp))
        Text("FINANZAS 360 MX", color = Color.White,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Preparando tu espacio financiero…", color = Color(0xFFB8BED3))
        Spacer(Modifier.height(22.dp))
        CircularProgressIndicator(color = Color(0xFF70D7B2))
    }
}

@Composable
private fun DatabaseStartupErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101426)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null,
            tint = Color(0xFFFF7D86), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No se pudo iniciar FINANZAS 360 MX", color = Color.White,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("La base de datos cifrada no pudo abrirse. Tus datos no se han eliminado.",
            color = Color(0xFFB8BED3))
        Spacer(Modifier.height(12.dp))
        Text(message.take(260), color = Color(0xFFFFB7BC), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Reintentar") }
    }
}

@Composable
private fun FinanceAppContent(db: FinanceDb, onLockChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(db) { onDispose { db.close() } }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var refresh by remember { mutableIntStateOf(0) }
    var snapshotVersion by remember { mutableIntStateOf(0) }
    var lastAutoSyncChange by remember { mutableLongStateOf(-1L) }
    var showTransaction by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var showRates by remember { mutableStateOf(false) }
    var txDraft by remember { mutableStateOf("") }
    var txAmountDraft by remember { mutableStateOf("") }
    var backupPassword by remember { mutableStateOf("") }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordAction by remember { mutableStateOf("EXPORT") }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    DisposableEffect(context) {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refreshOnMain() { Handler(Looper.getMainLooper()).post { refresh++ } }
            override fun onAvailable(network: android.net.Network) { refreshOnMain() }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) refreshOnMain()
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    LaunchedEffect(refresh) {
        val modifiedAt = withContext(Dispatchers.IO) {
            db.ensureCreditCardStatements()
            db.pendingReminders().forEach { ReminderScheduler.schedule(context, it) }
            db.recordNetWorthSnapshot()
            db.lastModifiedAt()
        }
        val config = CloudSyncSettings.load(context)
        if (CloudSyncSettings.isAutoSyncEnabled(context) && modifiedAt != lastAutoSyncChange && config.endpoint.isNotBlank() && isNetworkValidated(context)) {
            val result = runCatching { withContext(Dispatchers.IO) { CloudSyncManager.sync(context, db, config) } }.getOrNull()
            if (result != null) lastAutoSyncChange = modifiedAt
            if (result?.status == "DOWNLOADED") {
                (context as? android.app.Activity)?.recreate()
                return@LaunchedEffect
            } else if (result?.status == "CONFLICT") toast(result.message)
        }
        snapshotVersion++
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (spoken.isNotBlank()) {
            txDraft = spoken
            txAmountDraft = extractAmountCandidate(spoken)
            tab = AppTab.MOVEMENTS
            showTransaction = true
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val image = InputImage.fromFilePath(context, uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                    .addOnSuccessListener { text ->
                        val recognized = text.text
                        val totalRegex = Regex("(?i)(total|importe total|monto total)[^0-9]{0,18}([0-9][0-9.,]*)")
                        val candidate = totalRegex.find(recognized)?.groupValues?.getOrNull(2)
                        txAmountDraft = candidate?.let { normalizeCapturedAmount(it) }.orEmpty()
                        txDraft = if (candidate != null) "Ticket escaneado. ${recognized.take(140)}" else "Ticket escaneado: ${recognized.take(180)}"
                        tab = AppTab.MOVEMENTS
                        showTransaction = true
                    }
                    .addOnFailureListener { toast("No se pudo leer el documento: ${it.message}") }
            } catch (e: Exception) {
                toast("No se pudo abrir la imagen: ${e.message}")
            }
        }
    }

    val photoUri = remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoUri.value != null) {
            try {
                val image = InputImage.fromFilePath(context, photoUri.value!!)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                    .addOnSuccessListener { text ->
                        val recognized = text.text
                        val candidate = Regex("(?i)(total|importe total|monto total)[^0-9]{0,18}([0-9][0-9.,]*)").find(recognized)?.groupValues?.getOrNull(2)
                        txAmountDraft = candidate?.let { normalizeCapturedAmount(it) }.orEmpty()
                        txDraft = if (candidate != null) "Ticket fotografiado. ${recognized.take(140)}" else "Ticket fotografiado: ${recognized.take(180)}"
                        tab = AppTab.MOVEMENTS
                        showTransaction = true
                    }
                    .addOnFailureListener { toast("No se pudo reconocer el texto: ${it.message}") }
            } catch (e: Exception) { toast("Error al procesar fotografía: ${e.message}") }
        }
    }

    fun launchCamera() {
        try {
            val file = File(context.cacheDir, "ticket_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "mx.finanzas360.app.fileprovider", file)
            photoUri.value = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) { toast("No se pudo abrir la cámara: ${e.message}") }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                db.checkpoint()
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(db.exportTransactionsCsv())
                }
                toast("Exportación CSV creada.")
            } catch (e: Exception) { toast("Error al exportar: ${e.message}") }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            try {
                db.checkpoint()
                db.close()
                EncryptedBackup.export(context, uri, backupPassword.toCharArray())
                toast("Respaldo cifrado creado. Conserva tu contraseña.")
            } catch (e: Exception) { toast("No se pudo crear el respaldo: ${e.message}") }
        }
        backupPassword = ""
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            passwordAction = "RESTORE"
            showPasswordDialog = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("FINANZAS 360 MX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Tu dinero, cuentas y objetivos en un solo lugar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val icons = listOf(Icons.Filled.Home, Icons.Filled.AccountBalanceWallet, Icons.Filled.ReceiptLong, Icons.Filled.PieChart, Icons.Filled.MoreHoriz)
                AppTab.values().forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; refresh++ },
                        icon = { Icon(icons[index], contentDescription = item.title) },
                        label = { Text(item.title, maxLines = 1) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == AppTab.MOVEMENTS) {
                FloatingActionButton(onClick = { txDraft = ""; txAmountDraft = ""; showTransaction = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Nuevo movimiento")
                }
            } else if (tab == AppTab.ACCOUNTS) {
                FloatingActionButton(onClick = { showAccount = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Nueva cuenta")
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.HOME -> HomeScreen(db, refresh + snapshotVersion, onAddTransaction = { txDraft = ""; txAmountDraft = ""; showTransaction = true }, onOpenBudgets = { tab = AppTab.BUDGETS })
                AppTab.ACCOUNTS -> AccountsScreen(db, refresh, onAdd = { showAccount = true })
                AppTab.MOVEMENTS -> TransactionsScreen(db, refresh, onAdd = { txDraft = ""; txAmountDraft = ""; showTransaction = true })
                AppTab.BUDGETS -> BudgetsScreen(db, refresh, onChanged = { refresh++ })
                AppTab.MORE -> MoreScreen(
                    db = db,
                    refresh = refresh,
                    onChanged = { refresh++ },
                    onLockChanged = onLockChanged,
                    onVoice = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe el gasto o ingreso")
                        }
                        try { voiceLauncher.launch(intent) } catch (_: Exception) { toast("El dispositivo no tiene reconocimiento de voz disponible.") }
                    },
                    onScan = { imageLauncher.launch("image/*") },
                    onCamera = { launchCamera() },
                    onExport = { exportLauncher.launch("FINANZAS360_movimientos.csv") },
                    onBackup = {
                        passwordAction = "EXPORT"
                        showPasswordDialog = true
                    },
                    onRestore = { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    onAddReminder = { showReminder = true },
                    onAddCategory = { showCategory = true },
                    onRates = { showRates = true }
                )
            }
        }
    }

    if (showTransaction) {
        AddTransactionDialog(
            db = db,
            initialText = txDraft,
            initialAmount = txAmountDraft,
            onDismiss = { showTransaction = false; txDraft = ""; txAmountDraft = "" },
            onSave = { kind, amount, currency, categoryId, accountId, targetId, method, description, date ->
                try {
                    db.insertTransaction(kind, amount, currency, categoryId, accountId, targetId, method, description, date)
                    if ((kind == "EXPENSE" || kind == "INCOME") && categoryId != null && date.length >= 7) {
                        val monthKey = date.take(7)
                        val summary = db.budgetSummaries(monthKey).firstOrNull { it.categoryId == categoryId && it.currency == currency }
                        if (summary != null && summary.budget > 0.0) {
                            val ratio = summary.actual / summary.budget * 100.0
                            if (kind == "EXPENSE") {
                                listOf(70, 85, 100).forEach { threshold ->
                                    if (ratio >= threshold && db.markBudgetAlertSent(monthKey, categoryId, currency, threshold)) {
                                        BudgetNotification.show(context, summary.categoryName, summary.actual, summary.budget, currency, threshold, false)
                                    }
                                }
                            } else if (ratio >= 100.0 && db.markBudgetAlertSent(monthKey, categoryId, currency, 100)) {
                                BudgetNotification.show(context, summary.categoryName, summary.actual, summary.budget, currency, 100, true)
                            }
                        }
                    }
                    showTransaction = false
                    txDraft = ""
                    txAmountDraft = ""
                    refresh++
                    toast("Movimiento guardado.")
                } catch (e: Exception) { toast("No se pudo guardar: ${e.message}") }
            }
        )
    }

    if (showAccount) {
        AddAccountDialog(
            onDismiss = { showAccount = false },
            onSave = { institution, name, type, currency, limit, cutoff, due, rate ->
                try {
                    db.addAccount(institution, name, type, currency, limit, cutoff, due, rate)
                    showAccount = false
                    refresh++
                    toast("Producto financiero agregado.")
                } catch (e: Exception) { toast("No se pudo guardar la cuenta: ${e.message}") }
            }
        )
    }

    if (showReminder) {
        AddReminderDialog(
            accounts = db.listAccounts(),
            onDismiss = { showReminder = false },
            onSave = { title, amount, currency, dueAt, repeat, accountId ->
                val id = db.addReminder(title, amount, currency, dueAt, repeat, accountId)
                db.listReminders().firstOrNull { it.id == id }?.let { ReminderScheduler.schedule(context, it) }
                showReminder = false
                refresh++
                toast("Recordatorio programado.")
            }
        )
    }

    if (showCategory) {
        AddCategoryDialog(
            categories = db.listCategories(),
            onDismiss = { showCategory = false },
            onSave = { name, type, parent ->
                db.addCategory(name, type, parent)
                showCategory = false
                refresh++
                toast("Categoría guardada.")
            }
        )
    }

    if (showRates) {
        ExchangeRatesDialog(
            rates = db.listRates(),
            onDismiss = { showRates = false },
            onSave = { base, quote, rate ->
                db.upsertRate(base, quote, rate)
                refresh++
                toast("Tipo de cambio guardado.")
            }
        )
    }

    if (showPasswordDialog) {
        PasswordDialog(
            title = if (passwordAction == "EXPORT") "Crear respaldo cifrado" else "Restaurar respaldo",
            confirmText = if (passwordAction == "EXPORT") "Crear" else "Restaurar",
            onDismiss = { showPasswordDialog = false; backupPassword = ""; pendingRestoreUri = null },
            onConfirm = { password ->
                showPasswordDialog = false
                if (passwordAction == "EXPORT") {
                    backupPassword = password
                    backupLauncher.launch("FINANZAS360_respaldo_cifrado.f360")
                } else {
                    try {
                        val uri = pendingRestoreUri ?: error("No se seleccionó respaldo.")
                        db.close()
                        EncryptedBackup.restore(context, uri, password.toCharArray())
                        toast("Respaldo restaurado y validado.")
                        (context as? android.app.Activity)?.recreate()
                    } catch (e: Exception) {
                        toast("No se pudo restaurar: ${e.message}")
                    } finally {
                        backupPassword = ""
                        pendingRestoreUri = null
                    }
                }
            }
        )
    }
}

private fun isNetworkValidated(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
private fun HomeScreen(db: FinanceDb, refresh: Int, onAddTransaction: () -> Unit, onOpenBudgets: () -> Unit) {
    val month = YearMonth.now().toString()
    val accounts = remember(refresh) { db.listAccounts() }
    val monthlyByCurrency = remember(refresh) { db.monthlyTotalsByCurrency(month) }
    val netWorth = remember(refresh) { db.netWorthByCurrency() }
    val reminders = remember(refresh) { db.pendingReminders().take(4) }
    val days = LocalDate.now().dayOfMonth
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF28234F), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resumen financiero", color = Color(0xFFD8D2FF), style = MaterialTheme.typography.labelLarge)
                    Text(YearMonth.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es", "MX"))).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Cuentas registradas: ${accounts.size}", color = Color(0xFFCBC7E8))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAddTransaction) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Registrar") }
                        OutlinedButton(onClick = onOpenBudgets) { Text("Presupuestos") }
                    }
                }
            }
        }
        item {
            Text("Patrimonio por moneda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (netWorth.isEmpty()) {
                EmptyHint("Agrega tus cuentas para comenzar a calcular tu patrimonio.")
            } else {
                netWorth.forEach { item ->
                    FinanceCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(item.currency, fontWeight = FontWeight.Bold)
                                Text("Activos ${MoneyFormat.format(item.assets, item.currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Deudas ${MoneyFormat.format(item.liabilities, item.currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(MoneyFormat.format(item.netWorth, item.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                color = if (item.netWorth >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            Text("Evolución del patrimonio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (netWorth.isEmpty()) EmptyHint("El historial comenzará a generarse cuando registres tus primeras cuentas y movimientos.")
        }
        items(netWorth) { item -> NetWorthHistoryCard(db, item.currency, refresh) }
        item {
            val converted = netWorth.map { item ->
                if (item.currency == "MXN") item.netWorth else db.convertAmount(item.netWorth, item.currency, "MXN")
            }
            if (netWorth.isNotEmpty() && converted.all { it != null }) {
                FinanceCard {
                    Text("Patrimonio equivalente en MXN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(MoneyFormat.format(converted.filterNotNull().sum(), "MXN"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Conversión con los tipos de cambio manuales que registraste.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (netWorth.any { it.currency != "MXN" }) {
                Text("Para consolidar todas las monedas en MXN, registra los tipos de cambio manuales.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text("Este mes · $month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (monthlyByCurrency.isEmpty()) {
                EmptyHint("Tus ingresos y gastos aparecerán aquí, separados por moneda.")
            }
        }
        items(monthlyByCurrency) { totals ->
            val forecast = FinanceInsights.projectedExpense(totals.expense, days, YearMonth.now().lengthOfMonth())
            FinanceCard {
                Text("Moneda ${totals.currency}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Ingresos", totals.income, totals.currency, Color(0xFF70D7B2), Modifier.weight(1f))
                    MetricCard("Gastos", totals.expense, totals.currency, Color(0xFFFF8992), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Ahorro real", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        Text(MoneyFormat.format(totals.savings, totals.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Tasa de ahorro", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        Text("${"%.1f".format(FinanceInsights.savingsRate(totals.income, totals.expense))}%", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Proyección de gastos al cierre: ${MoneyFormat.format(forecast, totals.currency)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text("Próximos recordatorios", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (reminders.isEmpty()) EmptyHint("Todavía no tienes recordatorios pendientes.")
        }
        items(reminders) { reminder ->
            ReminderCard(reminder)
        }
        item {
            FinanceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Análisis local", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                monthlyByCurrency.isEmpty() -> "Registra movimientos para generar tus primeros análisis."
                                monthlyByCurrency.any { it.savings < 0.0 } -> "En al menos una moneda, tus gastos superan tus ingresos este mes. Revisa ese presupuesto."
                                else -> "Análisis calculado por moneda: ingresos, gastos, ahorro real y proyección al cierre."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetWorthHistoryCard(db: FinanceDb, currency: String, refresh: Int) {
    val history = remember(refresh, currency) { db.netWorthHistory(currency, 30) }
    val latest = history.lastOrNull()
    FinanceCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(currency, fontWeight = FontWeight.Bold)
            if (latest != null) Text(MoneyFormat.format(latest.netWorth, currency), fontWeight = FontWeight.Bold)
        }
        if (history.size < 2) {
            Text("Se requieren al menos dos capturas diarias para mostrar la tendencia.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val lineColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val values = history.map { it.netWorth }
                val minValue = values.minOrNull() ?: 0.0
                val maxValue = values.maxOrNull() ?: 1.0
                val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = if (values.size == 1) 0f else size.width * index / (values.size - 1)
                    val y = size.height - (((value - minValue) / range).toFloat() * (size.height - 12f) + 6f)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(history.first().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(history.last().date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AccountsScreen(db: FinanceDb, refresh: Int, onAdd: () -> Unit) {
    val accounts = remember(refresh) { db.listAccounts() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Mis productos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Bancos, efectivo, tarjetas e inversiones", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onAdd) { Icon(Icons.Filled.Add, null); Text("Agregar") }
            }
        }
        if (accounts.isEmpty()) item { EmptyHint("Todavía no hay cuentas. Agrega tu primer banco o producto financiero.") }
        items(accounts) { account ->
            FinanceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (account.type) {
                            "CREDIT_CARD" -> Icons.Filled.CreditCard
                            "INVESTMENT" -> Icons.Filled.TrendingUp
                            "LOAN" -> Icons.Filled.AccountBalance
                            else -> Icons.Filled.AccountBalanceWallet
                        }, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.name, fontWeight = FontWeight.Bold)
                        Text("${account.institution} · ${accountTypeLabel(account.type)} · ${account.currency}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (account.type == "CREDIT_CARD") {
                            val available = account.creditLimit - account.balance
                            Text("Disponible: ${MoneyFormat.format(available, account.currency)} · Corte ${account.cutoffDay} / Pago ${account.dueDay}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        } else if ((account.type == "INVESTMENT" || account.type == "SAVINGS") && account.annualRate > 0) {
                            Text("Tasa anual registrada: ${account.annualRate}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(MoneyFormat.format(account.balance, account.currency), fontWeight = FontWeight.Bold,
                        color = if (account.type == "CREDIT_CARD" || account.type == "LOAN") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun TransactionsScreen(db: FinanceDb, refresh: Int, onAdd: () -> Unit) {
    val transactions = remember(refresh) { db.listTransactions() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Movimientos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Ingresos, gastos, transferencias y rendimientos confirmados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onAdd) { Icon(Icons.Filled.Add, null); Text("Nuevo") }
            }
        }
        if (transactions.isEmpty()) item { EmptyHint("Aquí aparecerán tus gastos, ingresos y transferencias.") }
        items(transactions) { tx ->
            FinanceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isIncome = tx.kind == "INCOME"
                    val isTransfer = tx.kind == "TRANSFER"
                    Icon(
                        if (isTransfer) Icons.Filled.SwapHoriz else if (isIncome) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        null, tint = if (isIncome) MaterialTheme.colorScheme.secondary else if (isTransfer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tx.description.ifBlank { if (isTransfer) "Transferencia" else tx.categoryName }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${tx.date} · ${if (isTransfer) "${tx.accountName} → ${tx.transferAccountName}" else "${tx.categoryName} · ${tx.accountName}"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Text(
                        (if (isIncome) "+" else if (isTransfer) "" else "−") + MoneyFormat.format(tx.amount, tx.currency),
                        fontWeight = FontWeight.Bold,
                        color = if (isIncome) MaterialTheme.colorScheme.secondary else if (isTransfer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetsScreen(db: FinanceDb, refresh: Int, onChanged: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var copyMessage by remember { mutableStateOf("") }
    val summaries = remember(refresh, month) { db.budgetSummaries(month.toString()) }
    val monthlyByCurrency = remember(refresh, month) { db.monthlyTotalsByCurrency(month.toString()) }
    val budgetCurrencies = (summaries.map { it.currency } + monthlyByCurrency.map { it.currency }).distinct().sorted()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Presupuestos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, null) }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es", "MX"))).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronRight, null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val copied = db.copyBudgets(month.minusMonths(1).toString(), month.toString())
                    copyMessage = "Se copiaron $copied presupuestos del mes anterior."
                    onChanged()
                }) { Icon(Icons.Filled.ContentCopy, null); Text("Copiar mes anterior") }
                Button(onClick = { showBudgetDialog = true }) { Icon(Icons.Filled.Add, null); Text("Presupuesto") }
            }
            if (copyMessage.isNotBlank()) Text(copyMessage, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
        }
        items(budgetCurrencies) { currency ->
            val plannedIncome = summaries.filter { it.currency == currency && it.categoryType == "INCOME" }.sumOf { it.budget }
            val plannedExpense = summaries.filter { it.currency == currency && it.categoryType == "EXPENSE" }.sumOf { it.budget }
            val real = monthlyByCurrency.firstOrNull { it.currency == currency } ?: CurrencyMonthlyTotals(currency, 0.0, 0.0)
            FinanceCard {
                Text("Resumen presupuestal · $currency", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Ingreso planeado", plannedIncome, currency, Color(0xFF70D7B2), Modifier.weight(1f))
                    MetricCard("Gasto planeado", plannedExpense, currency, Color(0xFFFF8992), Modifier.weight(1f))
                }
                Text("Ahorro planeado: ${MoneyFormat.format(plannedIncome - plannedExpense, currency)}", fontWeight = FontWeight.Bold)
                Text("Ahorro real: ${MoneyFormat.format(real.savings, currency)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (summaries.isEmpty()) item { EmptyHint("Configura categorías y montos para crear tu presupuesto de este mes.") }
        items(summaries) { summary ->
            val isExpense = summary.categoryType == "EXPENSE"
            val percent = FinanceInsights.budgetPercent(summary.actual, summary.budget)
            FinanceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(summary.categoryName, fontWeight = FontWeight.Bold)
                        Text(if (isExpense) "Gasto ejecutado" else "Ingreso alcanzado", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    val displayedPercent = if (summary.budget <= 0.0) 0 else ((summary.actual / summary.budget) * 100.0).toInt()
                    Text("$displayedPercent%", fontWeight = FontWeight.Bold,
                        color = if (isExpense && summary.actual > summary.budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
                LinearProgressIndicator(
                    progress = { percent },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (isExpense && summary.actual > summary.budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Real: ${MoneyFormat.format(summary.actual, summary.currency)}", style = MaterialTheme.typography.bodySmall)
                    Text("Meta: ${MoneyFormat.format(summary.budget, summary.currency)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (isExpense && summary.actual > summary.budget) "Excedido por ${MoneyFormat.format(summary.actual - summary.budget, summary.currency)}"
                    else if (!isExpense && summary.actual > summary.budget) "Superaste el ingreso presupuestado por ${MoneyFormat.format(summary.actual - summary.budget, summary.currency)}"
                    else if (isExpense) "Disponible: ${MoneyFormat.format(summary.budget - summary.actual, summary.currency)}"
                    else "Faltante para la meta: ${MoneyFormat.format(summary.budget - summary.actual, summary.currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isExpense && summary.actual > summary.budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (showBudgetDialog) {
        AddBudgetDialog(
            db = db, month = month.toString(),
            onDismiss = { showBudgetDialog = false },
            onSave = { categoryId, amount, currency, propagate ->
                if (propagate) db.propagateBudget(categoryId, month.toString(), amount, currency, 24)
                else db.upsertBudget(month.toString(), categoryId, amount, currency)
                showBudgetDialog = false
                onChanged()
            }
        )
    }
}

@Composable
private fun MoreScreen(
    db: FinanceDb, refresh: Int, onChanged: () -> Unit, onLockChanged: (Boolean) -> Unit,
    onVoice: () -> Unit, onScan: () -> Unit, onCamera: () -> Unit,
    onExport: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
    onAddReminder: () -> Unit, onAddCategory: () -> Unit, onRates: () -> Unit
) {
    val context = LocalContext.current
    val reminders = remember(refresh) { db.listReminders() }
    val categories = remember(refresh) { db.listCategories() }
    var lockEnabled by remember(refresh) { mutableStateOf(AppLockSettings.isEnabled(context)) }
    val lockAuthenticators = if (Build.VERSION.SDK_INT >= 30)
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    else BiometricManager.Authenticators.BIOMETRIC_STRONG
    val canUseLock = BiometricManager.from(context).canAuthenticate(lockAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    var showCards by remember { mutableStateOf(false) }
    var showInvestments by remember { mutableStateOf(false) }
    var showCashFlow by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }
    var showCloudSync by remember { mutableStateOf(false) }
    var paymentStatement by remember { mutableStateOf<CreditStatement?>(null) }
    var minimumStatement by remember { mutableStateOf<CreditStatement?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Herramientas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Planeación, análisis, seguridad y sincronización", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item {
            FinanceCard {
                Text("Registro inteligente", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onVoice, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(4.dp)); Text("Por voz") }
                    OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(4.dp)); Text("Foto") }
                }
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.ImageSearch, null); Spacer(Modifier.width(8.dp)); Text("Leer imagen existente") }
            }
        }
        item {
            FinanceCard {
                Text("Control financiero avanzado", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { showCards = true; db.ensureCreditCardStatements() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CreditCard, null); Spacer(Modifier.width(8.dp)); Text("Tarjetas y estados de cuenta")
                }
                OutlinedButton(onClick = { showInvestments = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.TrendingUp, null); Spacer(Modifier.width(8.dp)); Text("Inversiones y rendimientos")
                }
                OutlinedButton(onClick = { showCashFlow = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Timeline, null); Spacer(Modifier.width(8.dp)); Text("Flujo de efectivo y proyecciones")
                }
                OutlinedButton(onClick = { showAssistant = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Asistente financiero IA")
                }
            }
        }
        item {
            FinanceCard {
                Text("Respaldo y nube", fontWeight = FontWeight.Bold)
                Text("La base de datos está cifrada localmente. Puedes crear respaldos protegidos por contraseña o sincronizar copias cifradas mediante un servidor WebDAV HTTPS compatible con Nextcloud o NAS.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showCloudSync = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CloudSync, null); Spacer(Modifier.width(8.dp)); Text("Configurar sincronización offline-cloud")
                }
                OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(8.dp)); Text("Crear respaldo cifrado") }
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restaurar respaldo") }
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Exportar movimientos CSV") }
            }
        }
        item {
            FinanceCard {
                Text("Seguridad y configuración", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bloqueo de la aplicación", fontWeight = FontWeight.SemiBold)
                        Text("Biometría fuerte o PIN/credencial segura del dispositivo al abrir la app", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = lockEnabled, enabled = canUseLock, onCheckedChange = { enabled ->
                        lockEnabled = enabled
                        AppLockSettings.setEnabled(context, enabled)
                        onLockChanged(enabled)
                    })
                }
                if (!canUseLock) Text("Para activar el bloqueo, configura biometría fuerte o un bloqueo seguro de pantalla en Android.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedButton(onClick = onAddReminder, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Crear recordatorio") }
                OutlinedButton(onClick = onAddCategory, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Category, null); Spacer(Modifier.width(8.dp)); Text("Crear categoría o subcategoría") }
                OutlinedButton(onClick = onRates, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.CurrencyExchange, null); Spacer(Modifier.width(8.dp)); Text("Tipos de cambio manuales") }
                Text("${categories.size} categorías configuradas", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Text("Recordatorios", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (reminders.isEmpty()) EmptyHint("No hay recordatorios registrados.")
        }
        items(reminders) { ReminderCard(it) }
        item {
            FinanceCard {
                Text("FINANZAS 360 MX · 0.2.0", fontWeight = FontWeight.Bold)
                Text("Funciona primero en el dispositivo. La IA online y la nube son opcionales; el análisis local y el registro permanecen disponibles sin conexión.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showCards) CardManagerDialog(db, refresh, onDismiss = { showCards = false }, onPay = { paymentStatement = it }, onSetMinimum = { minimumStatement = it })
    paymentStatement?.let { statement ->
        CardPaymentDialog(db, statement, onDismiss = { paymentStatement = null }, onSave = { sourceId, amount, date, note ->
            try {
                db.recordCardPayment(statement.id, sourceId, amount, date, note)
                paymentStatement = null
                onChanged()
            } catch (e: Exception) {
                Toast.makeText(context, "No se pudo registrar el pago: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
    minimumStatement?.let { statement ->
        CardMinimumDialog(statement, onDismiss = { minimumStatement = null }, onSave = { amount ->
            db.setStatementMinimum(statement.id, amount)
            minimumStatement = null
            onChanged()
        })
    }
    if (showInvestments) InvestmentDialog(db, refresh, onDismiss = { showInvestments = false }, onChanged = onChanged)
    if (showCashFlow) CashFlowDialog(db, refresh, onDismiss = { showCashFlow = false }, onChanged = onChanged)
    if (showAssistant) FinanceAssistantDialog(db, onDismiss = { showAssistant = false })
    if (showCloudSync) CloudSyncDialog(db, onDismiss = { showCloudSync = false }, onChanged = onChanged)
}

@Composable
private fun CardManagerDialog(db: FinanceDb, refresh: Int, onDismiss: () -> Unit, onPay: (CreditStatement) -> Unit, onSetMinimum: (CreditStatement) -> Unit) {
    val statements = remember(refresh) { db.listCreditStatements() }
    val accounts = remember(refresh) { db.listAccounts().filter { it.type == "CREDIT_CARD" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tarjetas y estados de cuenta") },
        text = {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("El pago para no generar intereses corresponde al saldo al corte pendiente. Registra el pago antes de la fecha límite indicada por tu banco.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (accounts.isEmpty()) EmptyHint("Agrega una tarjeta de crédito con día de corte y fecha límite de pago.")
                if (statements.isEmpty() && accounts.isNotEmpty()) EmptyHint("Aún no hay cortes generados. Se crean cuando llega el día de corte.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(statements) { item ->
                        FinanceCard {
                            Text("${item.accountName} · ${item.period}", fontWeight = FontWeight.Bold)
                            Text("Saldo al corte: ${MoneyFormat.format(item.statementBalance, item.currency)}", style = MaterialTheme.typography.bodySmall)
                            Text("Pago para no generar intereses: ${MoneyFormat.format(item.remaining, item.currency)}", color = if (item.remaining > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                            Text("Fecha límite: ${item.dueDate} · Pagado: ${MoneyFormat.format(item.paidAmount, item.currency)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.statementBalance <= 0.009) Text("Sin pago requerido para este corte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (item.minimumPayment > 0) Text("Pago mínimo capturado: ${MoneyFormat.format(item.minimumPayment, item.currency)}", style = MaterialTheme.typography.labelSmall)
                            else Text("Pago mínimo: consulta el estado de cuenta de tu banco", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val status = when {
                                item.statementBalance <= 0.009 -> "Sin saldo al corte"
                                item.isPaid && item.isPaidOnTime -> "Liquidado en o antes de la fecha límite"
                                item.isPaid -> "Liquidado después de la fecha límite"
                                runCatching { LocalDate.parse(item.dueDate).isBefore(LocalDate.now()) }.getOrDefault(false) -> "VENCIDO · requiere atención"
                                else -> "Pendiente"
                            }
                            Text(status, color = if (item.isPaidOnTime) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                            OutlinedButton(onClick = { onSetMinimum(item) }, modifier = Modifier.fillMaxWidth()) { Text("Capturar pago mínimo del banco") }
                            if (item.remaining > 0) Button(onClick = { onPay(item) }, modifier = Modifier.fillMaxWidth()) { Text("Registrar pago") }
                        }
                    }
                }
                Text("El importe y la fecha del banco son los datos definitivos. La app calcula el saldo con los movimientos registrados; no sustituye el estado de cuenta bancario.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun CardMinimumDialog(statement: CreditStatement, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var amount by remember(statement.id) { mutableStateOf(statement.minimumPayment.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pago mínimo bancario") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Captura el pago mínimo que aparece en el estado de cuenta oficial. No se estima automáticamente porque depende del banco y del contrato.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(amount, { amount = it }, label = { Text("Pago mínimo (${statement.currency})") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = {
            val value = amount.replace(",", ".").toDoubleOrNull() ?: -1.0
            if (value >= 0 && value <= statement.statementBalance) onSave(value)
        }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun CardPaymentDialog(
    db: FinanceDb, statement: CreditStatement, onDismiss: () -> Unit,
    onSave: (Long, Double, String, String) -> Unit
) {
    val sources = remember(statement.id) { db.listAccounts().filter { it.id != statement.accountId && it.currency == statement.currency && it.type in setOf("CHECKING", "SAVINGS", "CASH", "MULTICURRENCY") } }
    var sourceId by remember { mutableLongStateOf(sources.firstOrNull()?.id ?: 0L) }
    var amount by remember { mutableStateOf(statement.remaining.toString()) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("Pago tarjeta ${statement.accountName} · ${statement.period}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pago de tarjeta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pendiente para no generar intereses: ${MoneyFormat.format(statement.remaining, statement.currency)}")
                if (sources.isEmpty()) Text("Necesitas una cuenta de cheques, ahorro o efectivo en ${statement.currency} para registrar el pago.", color = MaterialTheme.colorScheme.error)
                else ChoiceField("Pagar desde", sources.map { it.id to "${it.name} · ${MoneyFormat.format(it.balance, it.currency)}" }, sourceId) { sourceId = it }
                OutlinedTextField(amount, { amount = it }, label = { Text("Importe del pago") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedButton(onClick = { amount = statement.remaining.toString() }, modifier = Modifier.fillMaxWidth()) { Text("Usar saldo total pendiente") }
                OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Referencia") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(enabled = sources.isNotEmpty(), onClick = {
            val value = amount.replace(",", ".").toDoubleOrNull() ?: 0.0
            if (sourceId > 0 && value > 0 && value <= statement.remaining + 0.01 && runCatching { LocalDate.parse(date) }.isSuccess) onSave(sourceId, value, date, note)
        }) { Text("Registrar pago") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun InvestmentDialog(
    db: FinanceDb,
    refresh: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val investments = remember(refresh) { db.investmentSummaries() }
    var selectedInvestment by remember { mutableStateOf<InvestmentSummary?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inversiones y rendimientos") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Las cuentas de ahorro, inversión y multidivisa con rendimiento pueden usar el cálculo automático diario, rendimientos reales capturados día por día o un importe mensual. Solo los importes manuales/confirmados se reflejan como ingreso realizado; no los registres otra vez como ingreso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (investments.isEmpty()) {
                    EmptyHint("Agrega una cuenta de tipo Inversión o Ahorro. Registra aportaciones como transferencias hacia esa cuenta.")
                }
                investments.forEach { item ->
                    val monthSummary = remember(refresh, item.accountId) {
                        db.investmentYieldMonthSummary(item.accountId, YearMonth.now().toString())
                    }
                    FinanceCard {
                        Text(item.name, fontWeight = FontWeight.Bold)
                        Text(
                            "Tasa anual configurada: ${"%.3f".format(item.annualRate)}% · Capital neto: ${MoneyFormat.format(item.principal, item.currency)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Valor de la cuenta: ${MoneyFormat.format(item.currentValue, item.currency)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rendimiento acumulado estimado/registrado: ${MoneyFormat.format(item.accruedInterest, item.currency)}",
                            color = if (item.accruedInterest >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                        Divider()
                        Text("Rendimiento de ${monthSummary.month}", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Estimado automático", style = MaterialTheme.typography.bodySmall)
                            Text(MoneyFormat.format(monthSummary.estimatedAmount, item.currency), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (monthSummary.mode == "AUTO") "Rendimiento estimado en saldo" else "Rendimiento aplicado al saldo",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                MoneyFormat.format(monthSummary.effectiveAmount, item.currency),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            when (monthSummary.mode) {
                                "DAILY" -> "Modalidad: rendimientos manuales diarios (${monthSummary.entryCount} registro(s)); automático desactivado este mes."
                                "MONTHLY" -> "Modalidad: importe mensual manual; desde la fecha de reconocimiento continúa la capitalización."
                                "ADJUSTED" -> "Modalidad: cálculo mensual ajustado; se aplica el importe corregido y continúa la capitalización posterior."
                                else -> "Modalidad: cálculo automático diario. Es una estimación y todavía no se cuenta como ingreso real."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { selectedInvestment = item },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Edit, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Registrar o ajustar rendimiento")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("30 días", style = MaterialTheme.typography.labelSmall)
                                Text(MoneyFormat.format(InvestmentCalculator.project(item.currentValue, item.annualRate, 30), item.currency), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("90 días", style = MaterialTheme.typography.labelSmall)
                                Text(MoneyFormat.format(InvestmentCalculator.project(item.currentValue, item.annualRate, 90), item.currency), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("1 año", style = MaterialTheme.typography.labelSmall)
                                Text(MoneyFormat.format(InvestmentCalculator.project(item.currentValue, item.annualRate, 365), item.currency), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )

    selectedInvestment?.let { account ->
        InvestmentYieldEditorDialog(
            db = db,
            account = account,
            refresh = refresh,
            onDismiss = { selectedInvestment = null },
            onSaved = {
                selectedInvestment = null
                onChanged()
            }
        )
    }
}

@Composable
private fun InvestmentYieldEditorDialog(
    db: FinanceDb,
    account: InvestmentSummary,
    refresh: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val currentMonth = YearMonth.now().toString()
    var month by remember(account.accountId) { mutableStateOf(currentMonth) }
    var mode by remember(account.accountId) { mutableStateOf("DAILY") }
    var date by remember(account.accountId) { mutableStateOf(LocalDate.now().toString()) }
    var amount by remember(account.accountId) { mutableStateOf("") }
    var note by remember(account.accountId) { mutableStateOf("") }
    var error by remember(account.accountId) { mutableStateOf("") }

    val summary = remember(account.accountId, month, refresh) {
        runCatching { db.investmentYieldMonthSummary(account.accountId, month) }.getOrNull()
    }
    val entries = remember(account.accountId, month, refresh) {
        runCatching { db.listInvestmentYieldEntries(account.accountId, month) }.getOrDefault(emptyList())
    }
    val parsedMonth = runCatching { YearMonth.parse(month) }.getOrNull()
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val parsedAmount = amount.trim().replace(",", ".").toDoubleOrNull()
    val dateBelongsToMonth = parsedMonth != null && parsedDate != null &&
        parsedDate.year == parsedMonth.year && parsedDate.monthValue == parsedMonth.monthValue
    val valid = summary != null && dateBelongsToMonth &&
        parsedDate != null && !parsedDate.isAfter(LocalDate.now()) &&
        parsedAmount != null && parsedAmount >= 0.0 &&
        (mode != "DAILY" || parsedAmount > 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rendimientos · ${account.name}") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Periodo mensual (AAAA-MM)")
                OutlinedTextField(
                    value = month,
                    onValueChange = { newMonth ->
                        month = newMonth
                        val parsed = runCatching { YearMonth.parse(newMonth) }.getOrNull()
                        if (parsed != null) {
                            date = if (parsed == YearMonth.now()) LocalDate.now().toString()
                                else parsed.atEndOfMonth().toString()
                            val newSummary = runCatching {
                                db.investmentYieldMonthSummary(account.accountId, newMonth)
                            }.getOrNull()
                            amount = when (mode) {
                                "ADJUSTED" -> {
                                    val value = newSummary?.let {
                                        if (it.mode == "ADJUSTED") it.recordedAmount else it.estimatedAmount
                                    }
                                    value?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                                }
                                "MONTHLY" -> newSummary?.takeIf { it.mode == "MONTHLY" }?.recordedAmount
                                    ?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                                else -> ""
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("2026-11") }
                )
                if (summary != null) {
                    Text(
                        "Estimación automática para el periodo: ${MoneyFormat.format(summary.estimatedAmount, account.currency)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Importe actualmente aplicado: ${MoneyFormat.format(summary.effectiveAmount, account.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Indica un mes válido que no sea futuro.", color = MaterialTheme.colorScheme.error)
                }
                Text("Modalidad de registro", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "DAILY", onClick = {
                            mode = "DAILY"
                            amount = entries.firstOrNull { it.date == date && it.type == "DAILY" }
                                ?.amount?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                        })
                        Column {
                            Text("Manual diario")
                            Text("Captura el abono de cada día.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "MONTHLY", onClick = {
                            mode = "MONTHLY"
                            amount = summary?.takeIf { it.mode == "MONTHLY" }?.recordedAmount
                                ?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                        })
                        Column {
                            Text("Manual mensual")
                            Text("Captura el total que abonó el banco en el mes.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "ADJUSTED", onClick = {
                            mode = "ADJUSTED"
                            val value = summary?.let { if (it.mode == "ADJUSTED") it.recordedAmount else it.estimatedAmount }
                            amount = value?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                        })
                        Column {
                            Text("Ajustar cálculo automático")
                            Text("Carga el estimado para que puedas corregirlo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = date,
                    onValueChange = {
                        date = it
                        if (mode == "DAILY") {
                            amount = entries.firstOrNull { entry -> entry.date == it && entry.type == "DAILY" }
                                ?.amount?.let { value -> String.format(Locale.US, "%.2f", value) } ?: ""
                        }
                    },
                    label = { Text(if (mode == "DAILY") "Fecha del abono (AAAA-MM-DD)" else "Fecha de reconocimiento (AAAA-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Importe del rendimiento (${account.currency})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota (opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (entries.isNotEmpty()) {
                    Divider()
                    Text("Registros del periodo", fontWeight = FontWeight.SemiBold)
                    entries.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${entry.date} · ${when (entry.type) {
                                        "DAILY" -> "Diario"
                                        "MONTHLY" -> "Mensual"
                                        else -> "Ajustado"
                                    }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (entry.note.isNotBlank()) {
                                    Text(entry.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(MoneyFormat.format(entry.amount, account.currency), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = {
                                db.deleteInvestmentYieldEntry(entry.id)
                                onSaved()
                            }) {
                                Icon(Icons.Filled.DeleteOutline, "Eliminar rendimiento")
                            }
                        }
                    }
                }
                Text(
                    when (mode) {
                        "DAILY" -> "Al registrar rendimientos diarios, el cálculo automático queda sustituido durante todo ese mes. Si cambias de total mensual a diario, se reemplaza ese registro. Repetir la fecha actualiza el importe."
                        "MONTHLY" -> "El total manual sustituye el cálculo automático hasta la fecha indicada; desde el día siguiente vuelve a capitalizarse la tasa configurada."
                        else -> "El importe corregido sustituye el estimado hasta la fecha indicada; desde el día siguiente continúa la capitalización automática."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                try {
                    val value = parsedAmount ?: 0.0
                    when (mode) {
                        "DAILY" -> db.saveDailyInvestmentYield(account.accountId, date, value, note)
                        "MONTHLY" -> db.saveMonthlyInvestmentYield(account.accountId, month, date, value, "MONTHLY", note)
                        "ADJUSTED" -> db.saveMonthlyInvestmentYield(account.accountId, month, date, value, "ADJUSTED", note)
                    }
                    onSaved()
                } catch (e: Exception) {
                    error = e.message ?: "No se pudo guardar el rendimiento."
                }
            }) { Text("Guardar rendimiento") }
        },
        dismissButton = {
            Row {
                if (summary != null && summary.mode != "AUTO") {
                    TextButton(onClick = {
                        db.clearMonthlyInvestmentYields(account.accountId, month)
                        onSaved()
                    }) { Text("Volver a automático") }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

@Composable
private fun CashFlowDialog(db: FinanceDb, refresh: Int, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    val forecast = remember(refresh) { db.cashFlowForecast(6) }
    val plans = remember(refresh) { db.listCashFlowPlans() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flujo de efectivo proyectado") },
        text = {
            Column(Modifier.heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Los meses futuros parten del promedio de los últimos tres meses completos y suman tus compromisos programados. Las monedas se proyectan por separado.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Programar ingreso o gasto") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(forecast) { row ->
                        FinanceCard {
                            Text("${row.month} · ${row.currency}", fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ingresos ${MoneyFormat.format(row.expectedIncome, row.currency)}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                                Text("Gastos ${MoneyFormat.format(row.expectedExpense, row.currency)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Saldo proyectado: ${MoneyFormat.format(row.closingBalance, row.currency)}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (plans.isNotEmpty()) {
                    Text("Compromisos programados", fontWeight = FontWeight.Bold)
                    plans.forEach { plan ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plan.title, style = MaterialTheme.typography.bodySmall)
                                Text("${plan.kind} · ${plan.nextDate} · ${plan.repeatRule}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(MoneyFormat.format(plan.amount, plan.currency), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { db.deleteCashFlowPlan(plan.id); onChanged() }) { Icon(Icons.Filled.DeleteOutline, "Eliminar") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
    if (showAdd) AddCashFlowPlanDialog(onDismiss = { showAdd = false }, onSave = { title, kind, amount, currency, date, repeat ->
        db.addCashFlowPlan(title, kind, amount, currency, date, repeat)
        showAdd = false
        onChanged()
    })
}

@Composable
private fun AddCashFlowPlanDialog(onDismiss: () -> Unit, onSave: (String, String, Double, String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("EXPENSE") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("MXN") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var repeat by remember { mutableStateOf("MONTHLY") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Programar flujo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Concepto") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Tipo", listOf("EXPENSE" to "Gasto / salida", "INCOME" to "Ingreso / entrada"), kind) { kind = it }
                OutlinedTextField(amount, { amount = it }, label = { Text("Importe") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Moneda", currencies.map { it to it }, currency) { currency = it }
                OutlinedTextField(date, { date = it }, label = { Text("Primera fecha (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Frecuencia", listOf("NONE" to "Una vez", "WEEKLY" to "Semanal", "MONTHLY" to "Mensual", "YEARLY" to "Anual"), repeat) { repeat = it }
            }
        },
        confirmButton = { Button(onClick = {
            val value = amount.replace(",", ".").toDoubleOrNull() ?: 0.0
            if (title.isNotBlank() && value > 0 && runCatching { LocalDate.parse(date) }.isSuccess) onSave(title, kind, value, currency, date, repeat)
        }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun FinanceAssistantDialog(db: FinanceDb, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AiSettingsStore.load(context)) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("El modo local funciona sin conexión. Puedes habilitar el proveedor online con tu propia API key.") }
    var loading by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Asistente financiero IA") },
        text = {
            Column(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("IA online opcional", fontWeight = FontWeight.SemiBold)
                        Text("Si está apagada, el análisis se realiza localmente.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.useOnline, onCheckedChange = { settings = settings.copy(useOnline = it) })
                }
                if (settings.useOnline) {
                    OutlinedTextField(settings.endpoint, { settings = settings.copy(endpoint = it) }, label = { Text("Endpoint compatible con OpenAI") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(settings.model, { settings = settings.copy(model = it) }, label = { Text("Modelo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(settings.apiKey, { settings = settings.copy(apiKey = it) }, label = { Text("API key (se cifra en el dispositivo)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text("La API key y el consumo del proveedor son independientes de ChatGPT Plus. Solo se envían agregados financieros resumidos; no se transmiten descripciones completas de movimientos.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(question, { question = it }, label = { Text("¿Qué deseas analizar?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { AiSettingsStore.save(context, settings) }, modifier = Modifier.weight(1f)) { Text("Guardar IA") }
                    Button(enabled = !loading && question.isNotBlank(), onClick = {
                        AiSettingsStore.save(context, settings)
                        loading = true
                        scope.launch {
                            answer = try { withContext(Dispatchers.IO) { FinanceAssistantService.ask(context, db, question, settings) } }
                                catch (e: Exception) { "No se pudo completar el análisis: ${e.message}" }
                            loading = false
                        }
                    }, modifier = Modifier.weight(1f)) { Text(if (loading) "Analizando…" else "Consultar") }
                }
                FinanceCard { Text(answer, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun CloudSyncDialog(db: FinanceDb, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(CloudSyncSettings.load(context)) }
    var autoSync by remember { mutableStateOf(CloudSyncSettings.isAutoSyncEnabled(context)) }
    var loading by remember { mutableStateOf(false) }
    var conflictDetected by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("Modo local: los datos permanecen en el teléfono hasta que configures un servidor WebDAV HTTPS.") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sincronización offline-cloud") },
        text = {
            Column(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Introduce la URL completa de un archivo .f360 en tu servidor WebDAV (por ejemplo Nextcloud o un NAS), usuario y contraseña. El contenido se cifra antes de salir del dispositivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(config.endpoint, { config = config.copy(endpoint = it) }, label = { Text("URL HTTPS del archivo remoto .f360") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(config.username, { config = config.copy(username = it) }, label = { Text("Usuario WebDAV") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(config.password, { config = config.copy(password = it) }, label = { Text("Contraseña WebDAV") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(config.encryptionPassword, { config = config.copy(encryptionPassword = it) }, label = { Text("Contraseña de cifrado (mínimo 8 caracteres)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Conserva la contraseña de cifrado: sin ella no se puede recuperar la copia. Si ambos dispositivos cambiaron desde la última sincronización, la app crea una copia de conflicto y no sobrescribe silenciosamente el archivo principal.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sincronización automática", fontWeight = FontWeight.SemiBold)
                        Text("Al abrir la app y cuando detecte cambios, si hay internet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoSync, onCheckedChange = { autoSync = it; CloudSyncSettings.save(context, config); CloudSyncSettings.setAutoSyncEnabled(context, it) })
                }
                Button(onClick = {
                    CloudSyncSettings.save(context, config)
                    CloudSyncSettings.setAutoSyncEnabled(context, autoSync)
                    result = "Configuración guardada de forma cifrada en el dispositivo."
                }, modifier = Modifier.fillMaxWidth()) { Text("Guardar configuración") }
                Button(enabled = !loading, onClick = {
                    CloudSyncSettings.save(context, config)
                    CloudSyncSettings.setAutoSyncEnabled(context, autoSync)
                    loading = true
                    result = "Conectando con WebDAV…"
                    scope.launch {
                        result = try {
                            val syncResult = withContext(Dispatchers.IO) { CloudSyncManager.sync(context, db, config) }
                            conflictDetected = syncResult.status == "CONFLICT"
                            if (syncResult.status == "DOWNLOADED") (context as? android.app.Activity)?.recreate()
                            "${syncResult.status}: ${syncResult.message}"
                        } catch (e: Exception) { "No se completó la sincronización: ${e.message}" }
                        loading = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Sincronizando…" else "Sincronizar ahora") }
                FinanceCard {
                    Text(result, style = MaterialTheme.typography.bodySmall)
                    if (conflictDetected) {
                        Text("Elige explícitamente qué versión debe quedar como principal:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { confirmAction = "UPLOAD" }, modifier = Modifier.fillMaxWidth()) { Text("Usar este dispositivo como principal") }
                        OutlinedButton(onClick = { confirmAction = "DOWNLOAD" }, modifier = Modifier.fillMaxWidth()) { Text("Descartar local y usar la nube") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
    confirmAction?.let { action ->
        val upload = action == "UPLOAD"
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(if (upload) "Conservar versión local" else "Reemplazar datos locales") },
            text = { Text(if (upload)
                "Se sobrescribirá el archivo principal de la nube con los datos de este teléfono. La copia de conflicto seguirá guardada en el servidor."
                else "Se reemplazará la base de datos de este teléfono con el archivo principal de la nube. Los cambios locales que no estén respaldados se perderán.") },
            confirmButton = { Button(enabled = !loading, onClick = {
                confirmAction = null
                loading = true
                scope.launch {
                    result = try {
                        val resolved = withContext(Dispatchers.IO) {
                            if (upload) CloudSyncManager.forceUpload(context, db, config)
                            else CloudSyncManager.forceDownload(context, db, config)
                        }
                        conflictDetected = false
                        if (resolved.status == "DOWNLOADED") (context as? android.app.Activity)?.recreate()
                        else onChanged()
                        "${resolved.status}: ${resolved.message}"
                    } catch (e: Exception) { "No se pudo resolver el conflicto: ${e.message}" }
                    loading = false
                }
            }) { Text(if (upload) "Sobrescribir nube" else "Reemplazar local") } },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun AddTransactionDialog(
    db: FinanceDb, initialText: String, initialAmount: String, onDismiss: () -> Unit,
    onSave: (String, Double, String, Long?, Long, Long?, String, String, String) -> Unit
) {
    val accounts = remember { db.listAccounts() }
    val allCategories = remember { db.listCategories() }
    var kind by remember { mutableStateOf("EXPENSE") }
    var amount by remember(initialAmount) { mutableStateOf(initialAmount) }
    var description by remember { mutableStateOf(initialText) }
    var accountId by remember { mutableLongStateOf(accounts.firstOrNull()?.id ?: 0L) }
    var targetId by remember { mutableLongStateOf(accounts.getOrNull(1)?.id ?: 0L) }
    var categoryId by remember { mutableLongStateOf(allCategories.firstOrNull { it.type == "EXPENSE" }?.id ?: 0L) }
    var method by remember {
        mutableStateOf(
            when (accounts.firstOrNull()?.type) {
                "CREDIT_CARD" -> "CREDIT"
                "CASH" -> "CASH"
                else -> "DEBIT"
            }
        )
    }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val account = accounts.firstOrNull { it.id == accountId }
    val categories = allCategories.filter { it.type == kind }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo movimiento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == "EXPENSE", onClick = { kind = "EXPENSE"; categoryId = allCategories.firstOrNull { it.type == "EXPENSE" }?.id ?: 0L }, label = { Text("Gasto") })
                    FilterChip(selected = kind == "INCOME", onClick = { kind = "INCOME"; categoryId = allCategories.firstOrNull { it.type == "INCOME" }?.id ?: 0L }, label = { Text("Ingreso") })
                    FilterChip(selected = kind == "TRANSFER", onClick = { kind = "TRANSFER" }, label = { Text("Transferir") })
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Importe") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Descripción / referencia") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Cuenta o tarjeta", accounts.map { it.id to "${it.name} (${it.currency})" }, accountId) {
                    accountId = it
                    val selectedAccount = accounts.firstOrNull { account -> account.id == it }
                    method = when (selectedAccount?.type) {
                        "CREDIT_CARD" -> "CREDIT"
                        "CASH" -> "CASH"
                        else -> "DEBIT"
                    }
                    if (targetId == it) targetId = accounts.firstOrNull { account -> account.id != it }?.id ?: 0L
                }
                if (kind == "TRANSFER") {
                    ChoiceField("Cuenta destino", accounts.filter { it.id != accountId }.map { it.id to "${it.name} (${it.currency})" }, targetId) { targetId = it }
                    Text("Las transferencias se excluyen de ingresos y gastos para evitar doble contabilización.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ChoiceField("Categoría", categories.map { it.id to it.name }, categoryId) { categoryId = it }
                    ChoiceField("Medio de pago", listOf("CASH" to "Efectivo", "DEBIT" to "Tarjeta de débito", "CREDIT" to "Tarjeta de crédito", "TRANSFER" to "Transferencia", "OTHER" to "Otro"), method) { method = it }
                }
                OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Moneda: ${account?.currency ?: "MXN"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (accounts.isEmpty()) Text("Primero agrega una cuenta o efectivo desde Cuentas.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = amount.replace(",", ".").toDoubleOrNull() ?: 0.0
                if (value <= 0 || account == null || date.length < 10) return@Button
                if (kind == "TRANSFER" && (targetId == 0L || targetId == accountId)) return@Button
                if (kind == "TRANSFER") {
                    val target = accounts.firstOrNull { it.id == targetId } ?: return@Button
                    if (target.currency != account.currency) return@Button
                }
                onSave(kind, value, account.currency, if (kind == "TRANSFER") null else categoryId.takeIf { it > 0 },
                    accountId, if (kind == "TRANSFER") targetId else null, method, description, date)
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Double, Int, Int, Double) -> Unit
) {
    var institution by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("CHECKING") }
    var currency by remember { mutableStateOf("MXN") }
    var limit by remember { mutableStateOf("0") }
    var cutoff by remember { mutableStateOf("0") }
    var due by remember { mutableStateOf("0") }
    var rate by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar producto financiero") },
        text = {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(institution, { institution = it }, label = { Text("Banco / institución") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Nombre de cuenta o tarjeta") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                ChoiceField("Tipo de producto", accountTypes.map { it.first to it.second }, type) { type = it }
                ChoiceField("Moneda", currencies.map { it to it }, currency) { currency = it }
                Text("Saldo inicial: $0.00. Registra los saldos desde cero mediante movimientos reales.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                if (type == "CREDIT_CARD") {
                    OutlinedTextField(limit, { limit = it }, label = { Text("Límite de crédito") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(cutoff, { cutoff = it }, label = { Text("Día de corte (1-31)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(due, { due = it }, label = { Text("Día límite de pago (1-31)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                if (type == "INVESTMENT" || type == "SAVINGS" || type == "MULTICURRENCY") {
                    OutlinedTextField(rate, { rate = it }, label = { Text("Tasa anual (%) si aplica") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) return@Button
                onSave(institution, name, type, currency,
                    limit.toDoubleOrNull() ?: 0.0, cutoff.toIntOrNull()?.coerceIn(0,31) ?: 0,
                    due.toIntOrNull()?.coerceIn(0,31) ?: 0, rate.replace(",", ".").toDoubleOrNull() ?: 0.0)
            }) { Text("Guardar cuenta") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AddBudgetDialog(db: FinanceDb, month: String, onDismiss: () -> Unit, onSave: (Long, Double, String, Boolean) -> Unit) {
    val categories = remember { db.listCategories() }
    var categoryId by remember { mutableLongStateOf(categories.firstOrNull()?.id ?: 0L) }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("MXN") }
    var propagate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presupuesto · $month") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceField("Categoría", categories.map { it.id to "${if (it.type == "INCOME") "Ingreso" else "Gasto"} · ${it.name}" }, categoryId) { categoryId = it }
                OutlinedTextField(amount, { amount = it }, label = { Text("Importe presupuestado") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                ChoiceField("Moneda", currencies.map { it to it }, currency) { currency = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = propagate, onCheckedChange = { propagate = it })
                    Text("Aplicar este importe a este mes y los siguientes 23 meses")
                }
                Text("Los meses anteriores no se modificarán.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = amount.replace(",", ".").toDoubleOrNull() ?: 0.0
                if (categoryId > 0 && value >= 0) onSave(categoryId, value, currency, propagate)
            }) { Text("Guardar presupuesto") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AddReminderDialog(
    accounts: List<Account>, onDismiss: () -> Unit,
    onSave: (String, Double, String, Long, String, Long?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("0") }
    var currency by remember { mutableStateOf("MXN") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var repeat by remember { mutableStateOf("NONE") }
    var accountId by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val parsed = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now().plusDays(1))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo recordatorio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Concepto / obligación") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount, { amount = it }, label = { Text("Importe (opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                ChoiceField("Moneda", currencies.map { it to it }, currency) { currency = it }
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        date = LocalDate.of(year, month + 1, day).toString()
                    }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("Fecha: $date") }
                ChoiceField("Repetición", listOf("NONE" to "Una vez", "WEEKLY" to "Semanal", "MONTHLY" to "Mensual", "YEARLY" to "Anual"), repeat) { repeat = it }
                if (accounts.isNotEmpty()) ChoiceField("Asociar cuenta", listOf(0L to "Sin asociar") + accounts.map { it.id to it.name }, accountId) { accountId = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isBlank()) return@Button
                val dueAt = parsed.atTime(9,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onSave(title, amount.replace(",", ".").toDoubleOrNull() ?: 0.0, currency, dueAt, repeat, accountId.takeIf { it > 0 })
            }) { Text("Programar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AddCategoryDialog(categories: List<Category>, onDismiss: () -> Unit, onSave: (String, String, Long?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("EXPENSE") }
    var parentId by remember { mutableLongStateOf(0L) }
    val parents = categories.filter { it.type == type && it.parentId == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva categoría") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                ChoiceField("Tipo", listOf("EXPENSE" to "Gasto", "INCOME" to "Ingreso"), type) { type = it; parentId = 0L }
                ChoiceField("Categoría padre", listOf(0L to "Categoría principal") + parents.map { it.id to it.name }, parentId) { parentId = it }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name, type, parentId.takeIf { it > 0 }) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ExchangeRatesDialog(
    rates: List<Triple<String,String,Double>>, onDismiss: () -> Unit,
    onSave: (String, String, Double) -> Unit
) {
    var quote by remember { mutableStateOf("USD") }
    var rate by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tipos de cambio manuales") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Registra cuántas unidades de la moneda destino equivalen a 1 unidad de la moneda base. No se descargan tasas automáticamente.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceField("Moneda destino", currencies.filter { it != "MXN" }.map { it to it }, quote) { quote = it }
                OutlinedTextField(rate, { rate = it }, label = { Text("1 MXN = ? $quote") }, modifier = Modifier.fillMaxWidth())
                rates.forEach { Text("${it.first} → ${it.second}: ${it.third}", style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = { Button(onClick = { val v = rate.replace(",", ".").toDoubleOrNull() ?: 0.0; if (v > 0) onSave("MXN", quote, v) }) { Text("Guardar tasa") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun PasswordDialog(title: String, confirmText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text("Contraseña (mínimo 8 caracteres)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Si pierdes esta contraseña, no será posible recuperar el respaldo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { if (password.length >= 8) onConfirm(password) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ChoiceField(label: String, options: List<Pair<Long, String>>, selected: Long, onSelected: (Long) -> Unit) {
    ChoiceField(label, options.map { it.first.toString() to it.second }, selected.toString()) { onSelected(it.toLongOrNull() ?: 0L) }
}

@Composable
private fun ChoiceField(label: String, options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second ?: "Seleccionar"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.second) }, onClick = { onSelected(option.first); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(reminder: ReminderItem) {
    FinanceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(reminder.title, fontWeight = FontWeight.SemiBold)
                Text(
                    Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            }
            if (reminder.amount > 0) Text(MoneyFormat.format(reminder.amount, reminder.currency), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MetricCard(label: String, amount: Double, currency: String, tint: Color, modifier: Modifier = Modifier) {
    FinanceCard(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(MoneyFormat.format(amount, currency), color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FinanceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
private fun EmptyHint(text: String) {
    FinanceCard {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private fun extractAmountCandidate(text: String): String {
    val raw = Regex("""(?<![A-Za-z])\d+(?:[.,]\d{1,2})?""").find(text)?.value ?: return ""
    return normalizeCapturedAmount(raw)
}

private fun normalizeCapturedAmount(raw: String): String {
    val value = raw.trim().replace(" ", "")
    return when {
        value.contains(",") && value.contains(".") ->
            if (value.lastIndexOf(',') > value.lastIndexOf('.')) value.replace(".", "").replace(",", ".") else value.replace(",", "")
        value.contains(",") -> {
            val decimals = value.length - value.lastIndexOf(',') - 1
            if (decimals in 1..2) value.replace(",", ".") else value.replace(",", "")
        }
        else -> value
    }
}

private val currencies = listOf("MXN", "USD", "EUR", "GBP", "CAD", "JPY", "CHF", "BRL")
private val accountTypes = listOf(
    "CHECKING" to "Cuenta de cheques",
    "SAVINGS" to "Cuenta de ahorro",
    "INVESTMENT" to "Cuenta de inversión",
    "CASH" to "Efectivo",
    "CREDIT_CARD" to "Tarjeta de crédito",
    "LOAN" to "Préstamo / deuda",
    "MULTICURRENCY" to "Cuenta en divisa"
)

private fun accountTypeLabel(type: String): String = accountTypes.firstOrNull { it.first == type }?.second ?: type

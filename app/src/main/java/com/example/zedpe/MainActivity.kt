package com.example.zedpe

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Constants
const val PHONEPE_PACKAGE_NAME = "com.phonepe.app"
const val GOOGLE_PAY_PACKAGE_NAME = "com.google.android.apps.nbu.paisa.user"
const val ZEDPE_ACCESSIBILITY_SERVICE_COMPONENT_NAME_STRING = "com.example.zedpe/.MyAccessibilityService"

val AppTitleColor = Color(0xFF1C1B1F)

class MainActivity : ComponentActivity() {

    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var paymentResultLauncher: ActivityResultLauncher<Intent>

    private val showPermissionRationaleDialog = mutableStateOf(false)
    private val showMainAppContent = mutableStateOf(false)
    private val showLoadingState = mutableStateOf(true)

    private val initialPayeeVpaFromIntent = mutableStateOf<String?>(null)
    private val initialAmountFromIntent = mutableStateOf<String?>(null)

    private val fullyQualifiedAccessibilityServiceName: String = ZEDPE_ACCESSIBILITY_SERVICE_COMPONENT_NAME_STRING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        accessibilitySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val serviceEnabled = isAccessibilityServiceEnabled(this, fullyQualifiedAccessibilityServiceName)
                showLoadingState.value = false
                if (serviceEnabled) {
                    showMainAppContent.value = true
                    showPermissionRationaleDialog.value = false
                } else {
                    Toast.makeText(this, "Accessibility permission not enabled. Link features may not work.", Toast.LENGTH_LONG).show()
                    showMainAppContent.value = true
                    showPermissionRationaleDialog.value = false
                }
            }

        paymentResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val response = result.data?.extras?.let { bundle ->
                    bundle.keySet().joinToString(", ") { key ->
                        "$key=${bundle.get(key) ?: "null"}"
                    }
                } ?: "No response"
                Log.d("UpiPayment", "ResultCode: ${result.resultCode}, Response: $response")
                when (result.resultCode) {
                    Activity.RESULT_OK -> Toast.makeText(this, "Payment successful: $response", Toast.LENGTH_LONG).show()
                    Activity.RESULT_CANCELED -> Toast.makeText(this, "Payment cancelled", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, "Payment failed: $response", Toast.LENGTH_LONG).show()
                }
            }

        if (savedInstanceState == null) {
            checkInitialPermissionOrShowContent()
        } else {
            showLoadingState.value = false
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppUI()
                }
            }
        }
    }

    @Composable
    private fun MainAppUI() {
        when {
            showLoadingState.value -> LoadingOrCheckingPermissionUI()
            showPermissionRationaleDialog.value -> PermissionRationaleDialogImpl(
                onGoToSettings = {
                    showPermissionRationaleDialog.value = false
                    showLoadingState.value = true
                    try {
                        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "Cannot open Accessibility Settings.", Toast.LENGTH_LONG).show()
                        showLoadingState.value = false
                        showMainAppContent.value = true
                    }
                },
                onExitApp = {
                    finishAffinity()
                }
            )
            showMainAppContent.value -> ZedPeAppScreen( // Updated call
                initialVpa = initialPayeeVpaFromIntent.value,
                initialAmount = initialAmountFromIntent.value,
                onInitiatePayment = { packageName, appName, payeeVpa, amount ->
                    initiateUpiPayment(this@MainActivity, packageName, appName, payeeVpa, amount)
                }
            )
            else -> {
                LoadingOrCheckingPermissionUI()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (intent != null) {
            super.onNewIntent(intent)
        }
        // Log.d("MainActivity", "onNewIntent received: Action=${intent?.action}, Data=${intent?.data}")
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Log.d("MainActivity", "Handling intent: Action=${intent?.action}, Data=${intent?.data}")
        if (intent?.action == Intent.ACTION_VIEW) {
            val data: Uri? = intent.data
            if (data != null && "zpi".equals(data.scheme, ignoreCase = true) && "pay".equals(data.host, ignoreCase = true)) {
                val payeeVpa = data.getQueryParameter("pa")
                val amount = data.getQueryParameter("am")
                Log.i("MainActivity", "Received ZPI intent: VPA=$payeeVpa, Amount=$amount")

                if (!payeeVpa.isNullOrBlank()) initialPayeeVpaFromIntent.value = payeeVpa
                if (!amount.isNullOrBlank()) initialAmountFromIntent.value = amount

                checkInitialPermissionOrShowContent()

            } else {
                if (showLoadingState.value) checkInitialPermissionOrShowContent()
            }
        } else {
            if (showLoadingState.value) checkInitialPermissionOrShowContent()
        }
    }

    private fun checkInitialPermissionOrShowContent() {
        showLoadingState.value = true
        val serviceEnabled = isAccessibilityServiceEnabled(this, fullyQualifiedAccessibilityServiceName)
        // Log.d("MainActivity", "checkInitialPermissionOrShowContent: Service Enabled = $serviceEnabled")

        if (serviceEnabled) {
            showMainAppContent.value = true
            showPermissionRationaleDialog.value = false
        } else {
            if (initialPayeeVpaFromIntent.value != null || initialAmountFromIntent.value != null) {
                showMainAppContent.value = true
                showPermissionRationaleDialog.value = false
                Toast.makeText(this, "Accessibility Link Helper is disabled. Links won't auto-fill.", Toast.LENGTH_LONG).show()
            } else {
                showMainAppContent.value = false
                showPermissionRationaleDialog.value = true
            }
        }
        showLoadingState.value = false
    }

    private fun initiateUpiPayment(
        context: Context,
        targetPackageName: String?,
        targetAppName: String,
        payeeVpa: String,
        amount: String
    ) {
        // Log.i("UpiPayment", "Attempting $targetAppName payment: VPA=$payeeVpa, Amount=$amount, TargetPkg: $targetPackageName")

        if (!payeeVpa.contains("@") || !payeeVpa.matches(Regex("[a-zA-Z0-9._\\-]+@[a-zA-Z0-9.\\-]+"))) {
            Log.e("UpiPayment", "Invalid VPA format: $payeeVpa")
            Toast.makeText(context, "Invalid UPI ID (e.g., user@bank)", Toast.LENGTH_SHORT).show()
            return
        }

        val amountDouble = amount.toDoubleOrNull()
        if (amountDouble == null || amountDouble <= 0) {
            Log.e("UpiPayment", "Invalid amount: $amount")
            Toast.makeText(context, "Enter a valid amount (e.g., 100.00)", Toast.LENGTH_SHORT).show()
            return
        }

        val formattedAmount = String.format("%.2f", amountDouble)
        val uriBuilder = Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", payeeVpa.trim())
            .appendQueryParameter("pn", payeeVpa.trim())
            .appendQueryParameter("am", formattedAmount)
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", "Payment via ZedPe App")

        val uri = uriBuilder.build()
        // Log.i("UpiPayment", "Constructed UPI URI: $uri")

        var appLaunched = false
        if (!targetPackageName.isNullOrEmpty() && isAppInstalled(context, targetPackageName) && isAppUpiReady(context, targetPackageName)) {
            val specificIntent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                setPackage(targetPackageName)
            }
            try {
                paymentResultLauncher.launch(specificIntent)
                appLaunched = true
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "Could not launch $targetAppName.", Toast.LENGTH_LONG).show()
            }
        } else if (!targetPackageName.isNullOrEmpty()) {
            Toast.makeText(context, "$targetAppName is not available.", Toast.LENGTH_LONG).show()
        }

        if (!appLaunched) {
            val genericIntent = Intent(Intent.ACTION_VIEW).apply { data = uri }
            if (genericIntent.resolveActivity(context.packageManager) != null) {
                try {
                    val chooser = Intent.createChooser(genericIntent, "Pay with...")
                    paymentResultLauncher.launch(chooser)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "No UPI app available.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "No UPI apps found.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isAppUpiReady(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val upiIntent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")).apply { setPackage(packageName) }
        val resolveInfoList = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(upiIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(upiIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfoList.isNotEmpty()
    }
}

@Composable
fun PermissionRationaleDialogImpl(
    onGoToSettings: () -> Unit,
    onExitApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Accessibility Permission Required") },
        text = { Text("ZedPe's Link Helper feature needs Accessibility permission to detect UPI links from other apps. Please enable it in Settings for automatic link handling.") },
        confirmButton = {
            Button(onClick = onGoToSettings) { Text("Go to Settings") }
        },
        dismissButton = {
            TextButton(onClick = onExitApp) { Text("Exit App", color = MaterialTheme.colorScheme.error) }
        }
    )
}

@Composable
fun LoadingOrCheckingPermissionUI() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(20.dp))
        Text("Initializing App...", fontSize = 18.sp, fontWeight = FontWeight.Medium) // Changed text
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Please wait while we check settings.",
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

fun isAccessibilityServiceEnabled(context: Context, serviceComponentString: String): Boolean {
    if (serviceComponentString.isBlank() || !serviceComponentString.contains('/')) {
        return false
    }
    val expectedComponentName = ComponentName.unflattenFromString(serviceComponentString) ?: return false
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledComponentName = ComponentName.unflattenFromString(componentNameString)
        if (enabledComponentName != null && enabledComponentName == expectedComponentName) {
            return true
        }
    }
    return false
}

// Updated ZedPeAppScreen Composable
@Composable
fun ZedPeAppScreen(
    initialVpa: String?,
    initialAmount: String?,
    onInitiatePayment: (String?, String, String, String) -> Unit
) {
    val context = LocalContext.current
    var payeeVpa by remember(initialVpa) { mutableStateOf(initialVpa ?: "") }
    var amount by remember(initialAmount) { mutableStateOf(initialAmount ?: "") }

    LaunchedEffect(initialVpa) {
        if (initialVpa != null && initialVpa != payeeVpa) payeeVpa = initialVpa
    }
    LaunchedEffect(initialAmount) {
        if (initialAmount != null && initialAmount != amount) amount = initialAmount
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp) // Vertical padding managed by Spacers
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Spacer to push content down
        Spacer(modifier = Modifier.height(40.dp)) // Adjust height as needed

        Text(
            text = "Payment", // << HEADING CHANGED
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTitleColor,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = payeeVpa,
            onValueChange = { payeeVpa = it.trim() },
            label = { Text("Payee UPI ID (e.g., user@bank)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            placeholder = { Text("user@okhdfcbank") }
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = {
                val newText = it.filter { char -> char.isDigit() || char == '.' }
                if (newText.count { c -> c == '.' } <= 1) {
                    val parts = newText.split('.')
                    if (parts.size == 2 && parts[1].length > 2) {
                        // Limit decimal places
                    } else if (newText == "." && amount.isEmpty()) {
                        amount = "0."
                    } else {
                        amount = newText
                    }
                }
            },
            label = { Text("Amount (INR)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            placeholder = { Text("100.00") }
        )
        Spacer(modifier = Modifier.height(32.dp))

        PaymentOptionButton(
            text = "Pay with PhonePe",
            onClick = {
                if (payeeVpa.isNotBlank() && amount.toDoubleOrNull()?.let { it > 0 } == true) {
                    onInitiatePayment(PHONEPE_PACKAGE_NAME, "PhonePe", payeeVpa, amount)
                } else {
                    Toast.makeText(context, "Enter valid UPI ID and Amount", Toast.LENGTH_SHORT).show()
                }
            },
            iconPainter = painterResource(id = R.drawable.phonepe), // Ensure this drawable exists
            buttonColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7), contentColor = Color.White)
        )
        Spacer(modifier = Modifier.height(16.dp))

        PaymentOptionButton(
            text = "Pay with Google Pay",
            onClick = {
                if (payeeVpa.isNotBlank() && amount.toDoubleOrNull()?.let { it > 0 } == true) {
                    onInitiatePayment(GOOGLE_PAY_PACKAGE_NAME, "Google Pay", payeeVpa, amount)
                } else {
                    Toast.makeText(context, "Enter valid UPI ID and Amount", Toast.LENGTH_SHORT).show()
                }
            },
            iconPainter = painterResource(id = R.drawable.googlepay), // Ensure this drawable exists
            buttonColors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4285F4)),
            borderColor = Color(0xFFDBDBDB)
        )
        // "Pay with any UPI App" button has been removed as requested
        Spacer(modifier = Modifier.height(24.dp)) // Optional: Add some space at the bottom
    }
}

@Composable
fun PaymentOptionButton(
    text: String,
    onClick: () -> Unit,
    iconPainter: Painter,
    buttonColors: ButtonColors,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
    borderColor: Color? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp),
        shape = RoundedCornerShape(12.dp),
        colors = buttonColors,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        border = borderColor?.let { BorderStroke(1.dp, it) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = iconPainter,
                contentDescription = "$text payment option",
                modifier = Modifier.size(30.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}
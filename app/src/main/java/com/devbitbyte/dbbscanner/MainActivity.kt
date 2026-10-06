package com.devbitbyte.dbbscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ScannerHome() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerHome() {
    val context = LocalContext.current
    var pageCount by remember { mutableIntStateOf(0) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Ready to scan") }

    val scanner = remember {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            pageCount = scan?.pages?.size ?: 0
            pdfUri = scan?.pdf?.uri
            status = if (pageCount > 0) "$pageCount page(s) scanned" else "No pages returned"
        } else status = "Scan cancelled"
    }

    fun startScan() {
        status = "Opening scanner..."
        scanner.getStartScanIntent(context as Activity)
            .addOnSuccessListener { sender ->
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                status = "Scanner unavailable: ${it.localizedMessage ?: "Unknown error"}"
            }
    }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("DBB Scanner") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
        ) {
            Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(72.dp))
            Text("Scan documents into clean PDFs", style = MaterialTheme.typography.headlineSmall)
            Text(status)
            Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DocumentScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan document")
            }
            pdfUri?.let { uri ->
                OutlinedButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share scanned PDF"))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PictureAsPdf, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share PDF")
                }
            }
            Text("Camera • Gallery • Auto crop • Perspective correction • Filters • Multi-page PDF",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

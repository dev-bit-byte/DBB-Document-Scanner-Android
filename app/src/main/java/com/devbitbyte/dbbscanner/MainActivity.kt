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
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScannerHome()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerHome() {
    val context = LocalContext.current
    val activity = context as? Activity

    var pageCount by remember { mutableIntStateOf(0) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var pdfFileName by remember { mutableStateOf("DBB_Scan.pdf") }
    var status by remember { mutableStateOf("Ready to scan") }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destinationUri ->
        if (destinationUri == null) {
            status = "Save cancelled"
            return@rememberLauncherForActivityResult
        }

        val sourceUri = pdfUri
        if (sourceUri == null) {
            status = "No PDF available to save"
            return@rememberLauncherForActivityResult
        }

        try {
            context.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Unable to read scanned PDF" }
                context.contentResolver.openOutputStream(destinationUri).use { output ->
                    requireNotNull(output) { "Unable to open save destination" }
                    input.copyTo(output)
                }
            }
            status = "PDF saved successfully"
        } catch (t: Throwable) {
            status = "Could not save PDF: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            status = "Scan cancelled"
            return@rememberLauncherForActivityResult
        }

        try {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            val sourcePdf = scan?.pdf?.uri
            pageCount = scan?.pages?.size ?: 0

            if (sourcePdf == null) {
                pdfUri = null
                status = if (pageCount > 0) {
                    "$pageCount page(s) scanned, but PDF was not returned"
                } else {
                    "No pages returned"
                }
                return@rememberLauncherForActivityResult
            }

            val scansDir = File(context.cacheDir, "scans").apply { mkdirs() }
            val fileName = "DBB_Scan_${System.currentTimeMillis()}.pdf"
            val localPdf = File(scansDir, fileName)

            context.contentResolver.openInputStream(sourcePdf).use { input ->
                requireNotNull(input) { "Unable to read scanner PDF" }
                localPdf.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            pdfUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                localPdf
            )
            pdfFileName = fileName
            status = "$pageCount page(s) scanned — PDF ready"
        } catch (t: Throwable) {
            pdfUri = null
            status = "PDF preparation failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    fun startScan() {
        val currentActivity = activity
        if (currentActivity == null) {
            status = "Unable to open scanner on this device"
            return
        }

        status = "Opening scanner..."

        try {
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
                .getStartScanIntent(currentActivity)
                .addOnSuccessListener { sender ->
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener {
                    status = "Scanner unavailable: ${it.localizedMessage ?: "Unknown error"}"
                }
        } catch (t: Throwable) {
            status = "Scanner could not start: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    fun sharePdf(uri: Uri) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("DBB Scanner PDF", uri)
            }
            context.startActivity(Intent.createChooser(share, "Share scanned PDF"))
        } catch (t: Throwable) {
            status = "Could not share PDF: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("DBB Scanner") })
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
        ) {
            Icon(
                Icons.Rounded.DocumentScanner,
                contentDescription = null,
                modifier = Modifier.size(72.dp)
            )

            Text(
                "Scan documents into clean PDFs",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(status)

            Button(
                onClick = { startScan() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.DocumentScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan document")
            }

            pdfUri?.let { uri ->
                OutlinedButton(
                    onClick = { savePdfLauncher.launch(pdfFileName) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save PDF")
                }

                OutlinedButton(
                    onClick = { sharePdf(uri) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share PDF")
                }
            }

            Text(
                "Camera • Gallery • Auto crop • Perspective correction • Filters • Multi-page PDF",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

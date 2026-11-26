package com.weddinggifts.tracker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Wedding Gift Tracker for Android.
 *
 * The whole app is the page in app/src/main/assets/index.html, shown in a WebView.
 * Records are stored on the phone by the WebView (IndexedDB). Nothing is sent anywhere.
 * This class only adds what a plain page can't do inside an app:
 * saving/sharing files, printing, picking a backup file, and opening phone/email links.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final int PICK_FILE = 1001;
    private static final int SCAN_ID = 1002;

    private WebView web;
    private WebView printView;                 // kept alive until printing finishes
    private ValueCallback<Uri[]> fileCallback;
    private File scanPhoto;                    // temporary photo of the ID, deleted right after reading

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Serves the built-in files under a private https address, so storage works normally.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;   // our own pages
                // tel:, sms:, mailto: and anything else go to the phone's own apps
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    toast("No app on this phone can open that link.");
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose a backup file"), PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("No file picker found on this phone.");
                    return false;
                }
                return true;
            }
            // alert(), confirm() and prompt() use the WebView's built-in dialogs.
        });

        web.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCAN_ID) {
            if (resultCode == RESULT_OK && scanPhoto != null && scanPhoto.length() > 0) {
                readIdBarcode(scanPhoto);
            } else {
                deleteScanPhoto();
                sendScanResult(errorJson("cancelled"));
            }
            return;
        }
        if (requestCode == PICK_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Methods the page can call as window.AndroidBridge.*  */
    private class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public void saveFile(final String base64, final String filename, final String mime) {
            runOnUiThread(() -> handleFile(base64, filename, mime));
        }

        @JavascriptInterface
        public void print(final String html, final String jobName) {
            runOnUiThread(() -> printHtml(html, jobName));
        }

        @JavascriptInterface
        public void scanId() {
            runOnUiThread(MainActivity.this::startIdPhoto);
        }
    }

    /* ---------------- Scan ID: photo of the license barcode, read on the phone ---------------- */

    /** Opens the camera app to photograph the barcode on the back of the ID. */
    private void startIdPhoto() {
        try {
            File dir = new File(getCacheDir(), "scans");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            scanPhoto = new File(dir, "id-scan.jpg");
            if (scanPhoto.exists()) //noinspection ResultOfMethodCallIgnored
                scanPhoto.delete();
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".files", scanPhoto);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, out);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(cam, SCAN_ID);
        } catch (ActivityNotFoundException e) {
            sendScanResult(errorJson("No camera app was found on this phone."));
        } catch (Exception e) {
            sendScanResult(errorJson("Couldn't open the camera: " + e.getMessage()));
        }
    }

    /** Reads the PDF417 barcode with ML Kit's built-in (offline) model. Keeps only name and address. */
    private void readIdBarcode(File photo) {
        final InputImage image;
        try {
            image = InputImage.fromFilePath(this, Uri.fromFile(photo));
        } catch (Exception e) {
            deleteScanPhoto();
            sendScanResult(errorJson("Couldn't read the photo."));
            return;
        }
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                .build();
        final BarcodeScanner scanner = BarcodeScanning.getClient(options);
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    JSONObject result = new JSONObject();
                    try {
                        if (barcodes.isEmpty()) {
                            result.put("error", "No ID barcode was found. Try again with the barcode filling most of the photo, in good light and in focus.");
                        } else {
                            Barcode b = barcodes.get(0);
                            Barcode.DriverLicense dl = b.getDriverLicense();
                            if (dl != null && (notEmpty(dl.getLastName()) || notEmpty(dl.getAddressStreet()))) {
                                result.put("last_name", nz(dl.getLastName()));
                                result.put("first_names", nz(dl.getFirstName()));
                                result.put("street", nz(dl.getAddressStreet()));
                                result.put("city", nz(dl.getAddressCity()));
                                result.put("state", nz(dl.getAddressState()));
                                result.put("zip", nz(dl.getAddressZip()));
                            } else {
                                // Let the page's own parser pick out the name and address fields.
                                result.put("raw", nz(b.getRawValue()));
                            }
                        }
                    } catch (JSONException ignored) {
                    }
                    deleteScanPhoto();
                    scanner.close();
                    sendScanResult(result.toString());
                })
                .addOnFailureListener(e -> {
                    deleteScanPhoto();
                    scanner.close();
                    sendScanResult(errorJson("Couldn't read the barcode: " + e.getMessage()));
                });
    }

    private void sendScanResult(String json) {
        if (web == null) return;
        web.evaluateJavascript("window.onIdScanned && window.onIdScanned(" + JSONObject.quote(json) + ")", null);
    }

    private static String errorJson(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private void deleteScanPhoto() {
        if (scanPhoto != null && scanPhoto.exists()) //noinspection ResultOfMethodCallIgnored
            scanPhoto.delete();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** Saves a copy to Downloads/Wedding Gifts, then offers the share sheet (Drive, Gmail, Messages...). */
    private void handleFile(String base64, String filename, String mime) {
        byte[] bytes;
        try {
            bytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            toast("Couldn't create the file.");
            return;
        }
        String name = (filename == null || filename.trim().isEmpty() ? "wedding-gifts" : filename)
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;

        boolean saved = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, type);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Wedding Gifts");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            out.write(bytes);
                            saved = true;
                        }
                    }
                }
            } catch (Exception e) {
                saved = false;
            }
        }

        try {
            File dir = new File(getCacheDir(), "exports");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
            Uri shareUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(type);
            send.putExtra(Intent.EXTRA_STREAM, shareUri);
            send.putExtra(Intent.EXTRA_SUBJECT, name);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, saved
                    ? "Saved in Downloads/Wedding Gifts. Also send it to…"
                    : "Save or send " + name));
        } catch (Exception e) {
            if (!saved) toast("Couldn't share the file.");
        }
        if (saved) toast("Saved in Downloads/Wedding Gifts");
    }

    /** Prints the report page using Android's print service (printer or "Save as PDF"). */
    private void printHtml(String html, final String jobName) {
        final boolean[] started = {false};
        printView = new WebView(this);
        printView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (started[0]) return;
                started[0] = true;
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) {
                    toast("Printing isn't available on this phone.");
                    return;
                }
                String job = (jobName == null || jobName.isEmpty()) ? "Wedding Gifts Report" : jobName;
                PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(job);
                PrintAttributes attrs = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.NA_LETTER.asLandscape())
                        .build();
                pm.print(job, adapter, attrs);
            }
        });
        printView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

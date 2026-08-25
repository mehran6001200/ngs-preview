package com.namiwin.glass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends Activity {
    private static final int REQ_CREATE_PDF = 901;
    private WebView webView;
    private PrintDocumentAdapter pendingAdapter;
    private String pendingFileName = "NamiWin.pdf";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void savePdf(final String invoiceNo) {
            runOnUiThread(() -> {
                String safe = invoiceNo == null ? "NamiWin" : invoiceNo.replaceAll("[^A-Za-z0-9_-]", "_");
                pendingFileName = "NamiWin-" + safe + ".pdf";
                webView.evaluateJavascript("(function(){var p=document.getElementById('invoicePanel');if(p)p.style.display='block';document.body.classList.add('android-pdf');})();", v -> {
                    pendingAdapter = webView.createPrintDocumentAdapter("NamiWin");
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/pdf");
                    intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);
                    startActivityForResult(intent, REQ_CREATE_PDF);
                });
            });
        }

        @JavascriptInterface
        public void printDocument() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("NamiWin");
                pm.print("NamiWin", adapter, new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build());
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CREATE_PDF || resultCode != RESULT_OK || data == null || pendingAdapter == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        final ParcelFileDescriptor pfd;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "w");
        } catch (Exception e) {
            Toast.makeText(this, "خطا در ایجاد فایل PDF", Toast.LENGTH_LONG).show();
            return;
        }
        if (pfd == null) return;

        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("namiwin", "NamiWin", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();

        final PrintDocumentAdapter adapter = pendingAdapter;
        adapter.onStart();
        adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd, new CancellationSignal(), new PrintDocumentAdapter.WriteResultCallback() {
                    @Override
                    public void onWriteFinished(PageRange[] pages) {
                        try { pfd.close(); } catch (IOException ignored) {}
                        adapter.onFinish();
                        pendingAdapter = null;
                        Toast.makeText(MainActivity.this, "PDF با موفقیت ذخیره شد", Toast.LENGTH_LONG).show();
                    }
                    @Override
                    public void onWriteFailed(CharSequence error) {
                        try { pfd.close(); } catch (IOException ignored) {}
                        adapter.onFinish();
                        pendingAdapter = null;
                        Toast.makeText(MainActivity.this, "ذخیره PDF ناموفق بود", Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override
            public void onLayoutFailed(CharSequence error) {
                try { pfd.close(); } catch (IOException ignored) {}
                adapter.onFinish();
                pendingAdapter = null;
                Toast.makeText(MainActivity.this, "آماده‌سازی PDF ناموفق بود", Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}

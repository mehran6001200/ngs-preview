package com.namiwin.glass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;

public class MainActivity extends Activity {
    private static final int REQ_CREATE_PDF = 901;
    private WebView webView;
    private String pendingFileName = "NamiWin.pdf";
    private final String enterPdfMode = "(function(){document.querySelectorAll('.edit,.top,.bottom').forEach(function(e){e.dataset.oldDisplay=e.style.display||'';e.style.display='none';});var d=document.getElementById('doc');if(d)d.style.display='block';document.body.style.background='#fff';document.body.style.padding='0';window.scrollTo(0,0);})();";
    private final String exitPdfMode = "(function(){document.querySelectorAll('.edit,.top,.bottom').forEach(function(e){e.style.display=e.dataset.oldDisplay||'';delete e.dataset.oldDisplay;});document.body.style.background='';document.body.style.padding='';})();";

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
                webView.evaluateJavascript(enterPdfMode, v -> webView.postDelayed(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/pdf");
                    intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);
                    startActivityForResult(intent, REQ_CREATE_PDF);
                }, 300));
            });
        }

        @JavascriptInterface
        public void printDocument() {
            runOnUiThread(() -> webView.evaluateJavascript(enterPdfMode, v -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("NamiWin");
                pm.print("NamiWin", adapter, new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build());
            }));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CREATE_PDF) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            webView.evaluateJavascript(exitPdfMode, null);
            return;
        }
        Uri uri = data.getData();
        webView.postDelayed(() -> writeWebViewPdf(uri), 350);
    }

    private void writeWebViewPdf(Uri uri) {
        final int pageWidth = 1240;
        final int pageHeight = 1754;
        int viewWidth = Math.max(1, webView.getWidth());
        float contentHeight = Math.max(webView.getHeight(), webView.getContentHeight() * webView.getScale());
        float scale = (float) pageWidth / (float) viewWidth;
        float scaledHeight = contentHeight * scale;
        int pageCount = Math.max(1, (int) Math.ceil(scaledHeight / pageHeight));

        PdfDocument pdf = new PdfDocument();
        try {
            for (int i = 0; i < pageCount; i++) {
                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create();
                PdfDocument.Page page = pdf.startPage(pageInfo);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(android.graphics.Color.WHITE);
                canvas.save();
                canvas.scale(scale, scale);
                canvas.translate(0, -(i * pageHeight / scale));
                webView.draw(canvas);
                canvas.restore();
                pdf.finishPage(page);
            }
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            if (pfd == null) throw new IOException("Could not open output file");
            FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
            pdf.writeTo(out);
            out.flush();
            out.close();
            pfd.close();
            Toast.makeText(this, "PDF با موفقیت ذخیره شد", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "خطا در ذخیره PDF", Toast.LENGTH_LONG).show();
        } finally {
            pdf.close();
            webView.evaluateJavascript(exitPdfMode, null);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}

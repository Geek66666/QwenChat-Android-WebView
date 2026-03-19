package com.codex.qwenchat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://chat.qwen.ai/";
    private static final String FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";
    private static final String TRUSTED_WEB_HOST = "chat.qwen.ai";
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int STARTUP_MEDIA_PERMISSION_REQUEST_CODE = 1002;
    private static final int WEB_MEDIA_PERMISSION_REQUEST_CODE = 1003;
    private static final String PERMISSION_PREFS_NAME = "qwenchat_permissions";
    private static final String KEY_STARTUP_MEDIA_PERMISSION_REQUESTED =
            "startup_media_permission_requested";
    private static final String[] STARTUP_MEDIA_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileChooser;
    private PermissionRequest pendingWebPermissionRequest;
    private boolean appPermissionRequestInFlight;
    private boolean pendingInitialLoad;
    private boolean permissionSettingsDialogShowing;
    private String userAgent;
    private Uri pendingImageCaptureUri;
    private Uri pendingVideoCaptureUri;
    private File pendingImageCaptureFile;
    private File pendingVideoCaptureFile;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        userAgent = resolveUserAgent();

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(3)
        );
        progressParams.gravity = Gravity.TOP;
        progressBar.setLayoutParams(progressParams);

        root.addView(webView);
        root.addView(progressBar);
        setContentView(root);

        configureMainWebView();
        boolean restoredState = savedInstanceState != null
                && webView.restoreState(savedInstanceState) != null;
        boolean waitingForStartupPermissions = maybeRequestStartupMediaPermissions();

        if (restoredState) {
            return;
        }
        if (waitingForStartupPermissions) {
            pendingInitialLoad = true;
            return;
        }
        webView.loadUrl(HOME_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureMainWebView() {
        applyWebSettings(webView);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNonHttpScheme(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                setTitle(title == null || title.trim().isEmpty() ? "Qwen Chat" : title);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleWebPermissionRequest(request);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermissionRequest == request) {
                    pendingWebPermissionRequest = null;
                }
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = filePathCallback;
                clearPendingCaptureOutputs(true);

                Intent chooserIntent = buildFileChooserIntent(fileChooserParams);
                if (chooserIntent == null) {
                    pendingFileChooser = null;
                    Toast.makeText(
                            MainActivity.this,
                            "No compatible file picker found",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }

                try {
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    clearPendingCaptureOutputs(true);
                    pendingFileChooser = null;
                    Toast.makeText(MainActivity.this, "No file picker found", Toast.LENGTH_SHORT)
                            .show();
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    Message resultMsg
            ) {
                final WebView popupWebView = new WebView(MainActivity.this);
                applyWebSettings(popupWebView);
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true);
                popupWebView.setWebViewClient(new WebViewClient() {
                    private boolean routed;

                    private void routeToMain(String url) {
                        if (routed) {
                            return;
                        }
                        routed = true;
                        if (url != null && !url.trim().isEmpty()) {
                            webView.loadUrl(url);
                        }
                        popupWebView.destroy();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        routeToMain(request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView view, String url, Bitmap favicon) {
                        routeToMain(url);
                    }
                });

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
            ) {
                enqueueDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyWebSettings(WebView targetWebView) {
        WebSettings settings = targetWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setTextZoom(100);
        settings.setUserAgentString(userAgent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }
    }

    private boolean maybeRequestStartupMediaPermissions() {
        String[] missingPermissions = getMissingStartupMediaPermissions();
        if (missingPermissions.length == 0) {
            return false;
        }

        SharedPreferences preferences = getSharedPreferences(PERMISSION_PREFS_NAME, MODE_PRIVATE);
        boolean requestedBefore =
                preferences.getBoolean(KEY_STARTUP_MEDIA_PERMISSION_REQUESTED, false);
        preferences.edit().putBoolean(KEY_STARTUP_MEDIA_PERMISSION_REQUESTED, true).apply();

        if (requestedBefore && hasPermanentlyDeniedAnyPermission(missingPermissions)) {
            showPermissionSettingsDialog();
            return false;
        }
        if (appPermissionRequestInFlight) {
            return false;
        }

        requestAppPermissions(missingPermissions, STARTUP_MEDIA_PERMISSION_REQUEST_CODE);
        return true;
    }

    private String[] getMissingStartupMediaPermissions() {
        ArrayList<String> missingPermissions = new ArrayList<>();
        for (String permission : STARTUP_MEDIA_PERMISSIONS) {
            if (!hasPermission(permission)) {
                missingPermissions.add(permission);
            }
        }
        return missingPermissions.toArray(new String[0]);
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null) {
            return;
        }
        if (!isTrustedPermissionRequest(request)) {
            request.deny();
            return;
        }

        String[] missingPermissions = getMissingPermissionsForResources(request.getResources());
        if (missingPermissions.length > 0) {
            replacePendingWebPermissionRequest(request);
            if (hasPermanentlyDeniedAnyPermission(missingPermissions)) {
                showPermissionSettingsDialog();
                resolvePendingWebPermissionRequest();
                return;
            }
            if (!appPermissionRequestInFlight) {
                requestAppPermissions(missingPermissions, WEB_MEDIA_PERMISSION_REQUEST_CODE);
            }
            return;
        }
        grantOrDenyWebPermissionRequest(request);
    }

    private boolean isTrustedPermissionRequest(PermissionRequest request) {
        Uri origin = request.getOrigin();
        if (origin == null) {
            return false;
        }
        String scheme = origin.getScheme();
        String host = origin.getHost();
        return "https".equalsIgnoreCase(scheme)
                && host != null
                && (TRUSTED_WEB_HOST.equalsIgnoreCase(host)
                || host.toLowerCase(Locale.US).endsWith("." + TRUSTED_WEB_HOST));
    }

    private String[] getMissingPermissionsForResources(String[] requestedResources) {
        ArrayList<String> missingPermissions = new ArrayList<>();
        if (requestedResources == null) {
            return new String[0];
        }
        for (String resource : requestedResources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && !hasPermission(Manifest.permission.RECORD_AUDIO)
                    && !missingPermissions.contains(Manifest.permission.RECORD_AUDIO)) {
                missingPermissions.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && !hasPermission(Manifest.permission.CAMERA)
                    && !missingPermissions.contains(Manifest.permission.CAMERA)) {
                missingPermissions.add(Manifest.permission.CAMERA);
            }
        }
        return missingPermissions.toArray(new String[0]);
    }

    private void replacePendingWebPermissionRequest(PermissionRequest request) {
        if (pendingWebPermissionRequest != null && pendingWebPermissionRequest != request) {
            pendingWebPermissionRequest.deny();
        }
        pendingWebPermissionRequest = request;
    }

    private void resolvePendingWebPermissionRequest() {
        if (pendingWebPermissionRequest == null) {
            return;
        }
        PermissionRequest request = pendingWebPermissionRequest;
        pendingWebPermissionRequest = null;
        grantOrDenyWebPermissionRequest(request);
    }

    private void requestAppPermissions(String[] permissions, int requestCode) {
        if (permissions == null || permissions.length == 0) {
            return;
        }
        appPermissionRequestInFlight = true;
        requestPermissions(permissions, requestCode);
    }

    private boolean hasPermanentlyDeniedAnyPermission(String[] permissions) {
        if (permissions == null) {
            return false;
        }
        for (String permission : permissions) {
            if (!hasPermission(permission) && !shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }

    private void showPermissionSettingsDialog() {
        if (permissionSettingsDialogShowing || isFinishing()) {
            return;
        }
        permissionSettingsDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("Enable Permissions")
                .setMessage(
                        "Microphone and camera access are blocked by the system. Open app settings and allow them for Qwen Chat."
                )
                .setCancelable(true)
                .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        permissionSettingsDialogShowing = false;
                    }
                })
                .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        permissionSettingsDialogShowing = false;
                        openAppPermissionSettings();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        permissionSettingsDialogShowing = false;
                    }
                })
                .show();
    }

    private void openAppPermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void loadHomeUrlIfNeeded() {
        if (!pendingInitialLoad || webView == null) {
            return;
        }
        pendingInitialLoad = false;
        webView.loadUrl(HOME_URL);
    }

    private void grantOrDenyWebPermissionRequest(PermissionRequest request) {
        String[] grantedResources = getGrantedWebPermissionResources(request.getResources());
        if (grantedResources.length > 0) {
            request.grant(grantedResources);
            return;
        }
        request.deny();
        Toast.makeText(
                this,
                "Camera or microphone permission is unavailable. Enable it in system settings if needed.",
                Toast.LENGTH_LONG
        ).show();
    }

    private String[] getGrantedWebPermissionResources(String[] requestedResources) {
        ArrayList<String> grantedResources = new ArrayList<>();
        if (requestedResources == null) {
            return new String[0];
        }
        for (String resource : requestedResources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                grantedResources.add(resource);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && hasPermission(Manifest.permission.CAMERA)) {
                grantedResources.add(resource);
            }
        }
        return grantedResources.toArray(new String[0]);
    }

    private Intent buildFileChooserIntent(WebChromeClient.FileChooserParams fileChooserParams) {
        String[] acceptTypes = normalizeAcceptTypes(
                fileChooserParams == null ? null : fileChooserParams.getAcceptTypes()
        );
        boolean captureOnly = fileChooserParams != null && fileChooserParams.isCaptureEnabled();

        ArrayList<Intent> captureIntents = buildCaptureIntents(acceptTypes);
        Intent pickerIntent = captureOnly ? null : buildPickerIntent(fileChooserParams, acceptTypes);
        if (pickerIntent == null && captureIntents.isEmpty()) {
            pickerIntent = buildPickerIntent(fileChooserParams, acceptTypes);
        }

        CharSequence chooserTitle =
                fileChooserParams != null && fileChooserParams.getTitle() != null
                        ? fileChooserParams.getTitle()
                        : "Select file";

        if (pickerIntent != null) {
            Intent chooser = Intent.createChooser(pickerIntent, chooserTitle);
            if (!captureIntents.isEmpty()) {
                chooser.putExtra(
                        Intent.EXTRA_INITIAL_INTENTS,
                        captureIntents.toArray(new Parcelable[0])
                );
            }
            return chooser;
        }

        if (captureIntents.isEmpty()) {
            return null;
        }

        Intent firstIntent = captureIntents.remove(0);
        if (captureIntents.isEmpty()) {
            return firstIntent;
        }

        Intent chooser = Intent.createChooser(firstIntent, chooserTitle);
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, captureIntents.toArray(new Parcelable[0]));
        return chooser;
    }

    private Intent buildPickerIntent(
            WebChromeClient.FileChooserParams fileChooserParams,
            String[] acceptTypes
    ) {
        Intent pickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
        pickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
        String primaryMimeType = resolvePickerMimeType(acceptTypes);
        pickerIntent.setType(primaryMimeType);
        String[] extraMimeTypes = resolveExtraMimeTypes(acceptTypes, primaryMimeType);
        if (extraMimeTypes.length > 0) {
            pickerIntent.putExtra(Intent.EXTRA_MIME_TYPES, extraMimeTypes);
        }
        pickerIntent.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                fileChooserParams != null
                        && fileChooserParams.getMode()
                        == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        );
        return pickerIntent;
    }

    private String[] normalizeAcceptTypes(String[] acceptTypes) {
        ArrayList<String> normalizedTypes = new ArrayList<>();
        if (acceptTypes == null) {
            return new String[0];
        }
        for (String rawType : acceptTypes) {
            if (rawType == null) {
                continue;
            }
            String[] splitTypes = rawType.split(",");
            for (String candidate : splitTypes) {
                String normalized = normalizeAcceptType(candidate);
                if (normalized != null && !normalizedTypes.contains(normalized)) {
                    normalizedTypes.add(normalized);
                }
            }
        }
        return normalizedTypes.toArray(new String[0]);
    }

    private String normalizeAcceptType(String rawType) {
        if (rawType == null) {
            return null;
        }
        String normalized = rawType.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return null;
        }
        int parameterIndex = normalized.indexOf(';');
        if (parameterIndex >= 0) {
            normalized = normalized.substring(0, parameterIndex).trim();
        }
        if (normalized.startsWith(".")) {
            String extension = normalized.substring(1);
            String mappedMimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension);
            normalized = mappedMimeType == null ? null : mappedMimeType.toLowerCase(Locale.US);
        }
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private String resolvePickerMimeType(String[] acceptTypes) {
        if (acceptTypes.length == 0) {
            return "*/*";
        }
        if (acceptTypes.length == 1) {
            return acceptTypes[0];
        }

        String topLevelType = null;
        for (String acceptType : acceptTypes) {
            if ("*/*".equals(acceptType)) {
                return "*/*";
            }
            int slashIndex = acceptType.indexOf('/');
            if (slashIndex <= 0) {
                return "*/*";
            }
            String candidateTopLevelType = acceptType.substring(0, slashIndex);
            if (topLevelType == null) {
                topLevelType = candidateTopLevelType;
            } else if (!topLevelType.equals(candidateTopLevelType)) {
                return "*/*";
            }
        }
        return topLevelType + "/*";
    }

    private String[] resolveExtraMimeTypes(String[] acceptTypes, String primaryMimeType) {
        if (acceptTypes.length <= 1) {
            return new String[0];
        }
        ArrayList<String> extraMimeTypes = new ArrayList<>();
        for (String acceptType : acceptTypes) {
            if (!acceptType.equals(primaryMimeType)) {
                extraMimeTypes.add(acceptType);
            }
        }
        return extraMimeTypes.toArray(new String[0]);
    }

    private ArrayList<Intent> buildCaptureIntents(String[] acceptTypes) {
        ArrayList<Intent> captureIntents = new ArrayList<>();

        if (acceptsMimeCategory(acceptTypes, "image")) {
            Intent imageIntent = createImageCaptureIntent();
            if (imageIntent != null) {
                captureIntents.add(imageIntent);
            }
        }

        if (acceptsMimeCategory(acceptTypes, "video")) {
            Intent videoIntent = createVideoCaptureIntent();
            if (videoIntent != null) {
                captureIntents.add(videoIntent);
            }
        }

        if (acceptsMimeCategory(acceptTypes, "audio")) {
            Intent audioIntent = createAudioCaptureIntent();
            if (audioIntent != null) {
                captureIntents.add(audioIntent);
            }
        }

        return captureIntents;
    }

    private boolean acceptsMimeCategory(String[] acceptTypes, String category) {
        if (acceptTypes.length == 0) {
            return true;
        }
        for (String acceptType : acceptTypes) {
            if ("*/*".equals(acceptType)
                    || (category + "/*").equals(acceptType)
                    || acceptType.startsWith(category + "/")) {
                return true;
            }
        }
        return false;
    }

    private Intent createImageCaptureIntent() {
        try {
            pendingImageCaptureFile = createCaptureFile("IMG_", ".jpg");
            pendingImageCaptureUri = CaptureFileProvider.uriForFile(this, pendingImageCaptureFile);
        } catch (IOException e) {
            clearPendingImageCaptureOutput(true);
            return null;
        }

        Intent imageIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (imageIntent.resolveActivity(getPackageManager()) == null) {
            clearPendingImageCaptureOutput(true);
            return null;
        }
        addCaptureOutput(imageIntent, pendingImageCaptureUri, "Captured image");
        return imageIntent;
    }

    private Intent createVideoCaptureIntent() {
        try {
            pendingVideoCaptureFile = createCaptureFile("VID_", ".mp4");
            pendingVideoCaptureUri = CaptureFileProvider.uriForFile(this, pendingVideoCaptureFile);
        } catch (IOException e) {
            clearPendingVideoCaptureOutput(true);
            return null;
        }

        Intent videoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        if (videoIntent.resolveActivity(getPackageManager()) == null) {
            clearPendingVideoCaptureOutput(true);
            return null;
        }
        addCaptureOutput(videoIntent, pendingVideoCaptureUri, "Captured video");
        return videoIntent;
    }

    private Intent createAudioCaptureIntent() {
        Intent audioIntent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
        return audioIntent.resolveActivity(getPackageManager()) == null ? null : audioIntent;
    }

    private void addCaptureOutput(Intent intent, Uri outputUri, String label) {
        intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), label, outputUri));
    }

    private File createCaptureFile(String prefix, String suffix) throws IOException {
        File captureDirectory = new File(getCacheDir(), "captures");
        if (!captureDirectory.exists() && !captureDirectory.mkdirs()) {
            throw new IOException("Unable to create capture directory");
        }
        return File.createTempFile(prefix, suffix, captureDirectory);
    }

    private boolean handleNonHttpScheme(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                startActivity(intent);
            } catch (Exception ignored) {
            }
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    private void enqueueDownload(
            String url,
            String requestUserAgent,
            String contentDisposition,
            String mimeType
    ) {
        try {
            String guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(guessedName);
            request.setDescription("Downloading from Qwen Chat");
            request.setMimeType(mimeType);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalFilesDir(
                    this,
                    Environment.DIRECTORY_DOWNLOADS,
                    guessedName
            );

            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.trim().isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            request.addRequestHeader(
                    "User-Agent",
                    requestUserAgent == null || requestUserAgent.trim().isEmpty()
                            ? FALLBACK_USER_AGENT
                            : requestUserAgent
            );

            DownloadManager downloadManager =
                    (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                downloadManager.enqueue(request);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "Unable to start download", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        if (pendingInitialLoad && !appPermissionRequestInFlight) {
            loadHomeUrlIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) {
            return;
        }
        if (pendingFileChooser == null) {
            clearPendingCaptureOutputs(true);
            return;
        }

        Uri[] results = parseFileChooserResult(resultCode, data);
        pendingFileChooser.onReceiveValue(results);
        pendingFileChooser = null;
    }

    private Uri[] parseFileChooserResult(int resultCode, Intent data) {
        ArrayList<Uri> resultUris = new ArrayList<>();
        if (resultCode != RESULT_OK) {
            clearPendingCaptureOutputs(true);
            return null;
        }

        if (data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    addUriIfPresent(resultUris, data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                addUriIfPresent(resultUris, data.getData());
            }
        }

        if (resultUris.isEmpty()) {
            addCompletedCapture(resultUris, pendingImageCaptureFile, pendingImageCaptureUri);
            addCompletedCapture(resultUris, pendingVideoCaptureFile, pendingVideoCaptureUri);
        }

        cleanupPendingCaptureOutputs(resultUris);
        return resultUris.isEmpty() ? null : resultUris.toArray(new Uri[0]);
    }

    private void addUriIfPresent(ArrayList<Uri> resultUris, Uri uri) {
        if (uri != null && !resultUris.contains(uri)) {
            resultUris.add(uri);
        }
    }

    private void addCompletedCapture(ArrayList<Uri> resultUris, File file, Uri uri) {
        if (file != null && file.exists() && file.length() > 0 && uri != null) {
            addUriIfPresent(resultUris, uri);
        }
    }

    private void cleanupPendingCaptureOutputs(ArrayList<Uri> usedUris) {
        if (pendingImageCaptureUri == null || !usedUris.contains(pendingImageCaptureUri)) {
            deleteQuietly(pendingImageCaptureFile);
        }
        if (pendingVideoCaptureUri == null || !usedUris.contains(pendingVideoCaptureUri)) {
            deleteQuietly(pendingVideoCaptureFile);
        }
        pendingImageCaptureFile = null;
        pendingImageCaptureUri = null;
        pendingVideoCaptureFile = null;
        pendingVideoCaptureUri = null;
    }

    private void clearPendingCaptureOutputs(boolean deleteFiles) {
        clearPendingImageCaptureOutput(deleteFiles);
        clearPendingVideoCaptureOutput(deleteFiles);
    }

    private void clearPendingImageCaptureOutput(boolean deleteFile) {
        if (deleteFile) {
            deleteQuietly(pendingImageCaptureFile);
        }
        pendingImageCaptureFile = null;
        pendingImageCaptureUri = null;
    }

    private void clearPendingVideoCaptureOutput(boolean deleteFile) {
        if (deleteFile) {
            deleteQuietly(pendingVideoCaptureFile);
        }
        pendingVideoCaptureFile = null;
        pendingVideoCaptureUri = null;
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STARTUP_MEDIA_PERMISSION_REQUEST_CODE
                && requestCode != WEB_MEDIA_PERMISSION_REQUEST_CODE) {
            return;
        }

        appPermissionRequestInFlight = false;
        resolvePendingWebPermissionRequest();
        if (requestCode == STARTUP_MEDIA_PERMISSION_REQUEST_CODE) {
            loadHomeUrlIfNeeded();
        }

        if (requestCode == STARTUP_MEDIA_PERMISSION_REQUEST_CODE
                && getMissingStartupMediaPermissions().length > 0) {
            Toast.makeText(
                    this,
                    "Camera or microphone permission was not fully granted. You can enable it later in system settings.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooser != null) {
            pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = null;
        }
        clearPendingCaptureOutputs(true);

        if (pendingWebPermissionRequest != null) {
            pendingWebPermissionRequest.deny();
            pendingWebPermissionRequest = null;
        }

        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dpToPx(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }

    private String resolveUserAgent() {
        try {
            String resolved = WebSettings.getDefaultUserAgent(this);
            if (resolved != null && !resolved.trim().isEmpty()) {
                return resolved;
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_USER_AGENT;
    }
}

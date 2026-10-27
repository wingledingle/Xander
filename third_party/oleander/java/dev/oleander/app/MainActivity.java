package dev.oleander.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts the Oleander web app (bundled in assets/web, including the
 * WebAssembly core) in a full-screen WebView served from a secure origin,
 * so IndexedDB, WebAssembly, the Gamepad API, motion sensors and audio work
 * exactly as in the browser, fully offline.
 *
 * Native additions: a folder picker (Storage Access Framework) whose
 * access is remembered and rescanned on start, ROM bytes streamed to the
 * page through {@code /native/file} (no base64 copies), exports to
 * Downloads, pinned home-screen shortcuts per game, and the Back button.
 */
public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START = ORIGIN + "/web/index.html";
    private static final String NATIVE_FILE = "/native/file";
    private static final int PICK_FILES = 1;
    private static final int PICK_TREE = 2;
    private static final String PREFS = "oleander";
    private static final String KEY_TREES = "trees";
    private static final String[] ROM_EXT = {".gba", ".agb", ".bin", ".mb", ".zip", ".gz"};

    private WebView web;
    private WebViewAssetLoader assets;
    private ValueCallback<Uri[]> pendingChooser;
    private Uri pendingOpen;
    private boolean pageReady;
    /** Single documents the page may read (opened with / shared to the app). */
    private final Set<String> allowedDocs = Collections.synchronizedSet(new HashSet<>());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);
        immersive();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "OleanderAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return intercept(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u.toString().startsWith(ORIGIN)) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingOpen != null) {
                    openDocument(pendingOpen);
                    pendingOpen = null;
                }
            }
        });
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                    return intercept(req.getUrl());
                }
            });
        }
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingChooser != null) pendingChooser.onReceiveValue(null);
                pendingChooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose files"), PICK_FILES);
                } catch (Exception e) {
                    pendingChooser = null;
                    return false;
                }
                return true;
            }
        });

        String play = getIntent() != null ? getIntent().getStringExtra("play") : null;
        if (state != null) web.restoreState(state);
        else web.loadUrl(play != null ? START + "?play=" + Uri.encode(play) : START);
        handleIntent(getIntent());
    }

    // ------------------------------------------------------------------
    // Request handling
    // ------------------------------------------------------------------

    private WebResourceResponse intercept(Uri url) {
        if (url.toString().startsWith(ORIGIN + NATIVE_FILE)) return nativeFile(url);
        WebResourceResponse r = assets.shouldInterceptRequest(url);
        if (r != null && url.getPath() != null) {
            String p = url.getPath();
            // WebAssembly streaming compilation needs the right MIME type.
            if (p.endsWith(".wasm")) r.setMimeType("application/wasm");
            else if (p.endsWith(".js")) r.setMimeType("text/javascript");
            else if (p.endsWith(".webmanifest")) r.setMimeType("application/manifest+json");
        }
        return r;
    }

    /** Streams a document the user granted access to (folder or single file). */
    private WebResourceResponse nativeFile(Uri url) {
        String doc = url.getQueryParameter("uri");
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-store");
        if (doc == null || !mayRead(doc)) {
            return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", headers, new java.io.ByteArrayInputStream(new byte[0]));
        }
        try {
            InputStream in = getContentResolver().openInputStream(Uri.parse(doc));
            return new WebResourceResponse("application/octet-stream", null, 200, "OK", headers, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    private boolean mayRead(String doc) {
        if (allowedDocs.contains(doc)) return true;
        Uri u = Uri.parse(doc);
        if (Build.VERSION.SDK_INT < 21 || !DocumentsContract.isDocumentUri(this, u)) return false;
        for (String tree : trees()) {
            Uri t = Uri.parse(tree);
            if (!t.getAuthority().equals(u.getAuthority())) continue;
            String treeDoc = DocumentsContract.getTreeDocumentId(t);
            String docId = DocumentsContract.getDocumentId(u);
            if (docId.equals(treeDoc) || docId.startsWith(treeDoc + "/") || doc.startsWith(tree + "/document/")) return true;
        }
        return false;
    }

    private static String nativeUrl(String doc) {
        return ORIGIN + NATIVE_FILE + "?uri=" + Uri.encode(doc);
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    private Set<String> trees() {
        return new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_TREES, new HashSet<>()));
    }

    private void rememberTree(Uri tree) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> t = trees();
        t.add(tree.toString());
        p.edit().putStringSet(KEY_TREES, t).apply();
    }

    private static boolean isRom(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : ROM_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** Lists ROM files below a tree (iterative, up to 8 levels deep). */
    private void listTree(Uri tree, JSONArray out) throws Exception {
        ContentResolver cr = getContentResolver();
        ArrayDeque<Object[]> todo = new ArrayDeque<>();
        todo.add(new Object[] {DocumentsContract.getTreeDocumentId(tree), 0});
        String[] cols = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        while (!todo.isEmpty()) {
            Object[] item = todo.poll();
            String parent = (String) item[0];
            int depth = (Integer) item[1];
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
            try (Cursor c = cr.query(children, cols, null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (depth < 8 && name != null && !name.startsWith(".")) todo.add(new Object[] {id, depth + 1});
                    } else if (name != null && isRom(name)) {
                        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        JSONObject o = new JSONObject();
                        o.put("name", name);
                        o.put("size", c.isNull(3) ? 0 : c.getLong(3));
                        o.put("lastModified", c.isNull(4) ? 0 : c.getLong(4));
                        o.put("url", nativeUrl(doc.toString()));
                        o.put("tree", tree.getLastPathSegment());
                        out.put(o);
                    }
                }
            }
        }
    }

    /** Scans folders on a background thread and hands the list to the page. */
    private void scanTrees(Set<String> list, boolean quiet) {
        new Thread(() -> {
            JSONArray out = new JSONArray();
            for (String t : list) {
                try {
                    listTree(Uri.parse(t), out);
                } catch (Exception e) {
                    // Access revoked or storage removed: forget it.
                    if (!quiet) runOnUiThread(() -> Toast.makeText(this, "Cannot read folder: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
            String js = "window.oleanderNativeScan && window.oleanderNativeScan(" + out + "," + quiet + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }, "oleander-scan").start();
    }

    // ------------------------------------------------------------------
    // Window / lifecycle
    // ------------------------------------------------------------------

    private void immersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String play = intent.getStringExtra("play");
        if (play != null) {
            web.loadUrl(START + "?play=" + Uri.encode(play));
            return;
        }
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        if (pageReady) openDocument(uri);
        else pendingOpen = uri;
    }

    /** A ROM opened from a file manager or shared to the app: import and play. */
    private void openDocument(Uri uri) {
        allowedDocs.add(uri.toString());
        long size = -1;
        String name = "game.gba";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
                int j = c.getColumnIndex(OpenableColumns.SIZE);
                if (j >= 0 && !c.isNull(j)) size = c.getLong(j);
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("url", nativeUrl(uri.toString()));
            if (size >= 0) o.put("size", size);
            // The page defines oleanderOpenNative only after it has loaded its settings and the wasm core, which is
            // after onPageFinished; calling it straight away dropped the file on a cold start. Wait for it (up to 15 s).
            web.evaluateJavascript("(function f(o, n) { if (window.oleanderOpenNative) window.oleanderOpenNative(o);"
                    + " else if (n < 300) setTimeout(function () { f(o, n + 1); }, 50); })(" + o + ", 0)", null);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == PICK_TREE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                rememberTree(tree);
                Toast.makeText(this, "Scanning folder…", Toast.LENGTH_SHORT).show();
                Set<String> one = new HashSet<>();
                one.add(tree.toString());
                scanTrees(one, false);
            }
            return;
        }
        if (req != PICK_FILES || pendingChooser == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                uris = new Uri[n];
                for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[] {data.getData()};
            }
        }
        pendingChooser.onReceiveValue(uris);
        pendingChooser = null;
    }

    // ---- Game controllers ----
    //
    // Android's WebView has no Gamepad API, so controller input is read
    // here and handed to the page as a gamepad with the W3C "standard"
    // button layout (window.oleanderNativePad), which the web app's
    // controller code and mapping screen use like any other pad.

    private static final int[][] PAD_KEYS = {
        {KeyEvent.KEYCODE_BUTTON_A, 0}, {KeyEvent.KEYCODE_BUTTON_B, 1},
        {KeyEvent.KEYCODE_BUTTON_X, 2}, {KeyEvent.KEYCODE_BUTTON_Y, 3},
        {KeyEvent.KEYCODE_BUTTON_L1, 4}, {KeyEvent.KEYCODE_BUTTON_R1, 5},
        {KeyEvent.KEYCODE_BUTTON_L2, 6}, {KeyEvent.KEYCODE_BUTTON_R2, 7},
        {KeyEvent.KEYCODE_BUTTON_SELECT, 8}, {KeyEvent.KEYCODE_BUTTON_START, 9},
        {KeyEvent.KEYCODE_BUTTON_THUMBL, 10}, {KeyEvent.KEYCODE_BUTTON_THUMBR, 11},
        {KeyEvent.KEYCODE_DPAD_UP, 12}, {KeyEvent.KEYCODE_DPAD_DOWN, 13},
        {KeyEvent.KEYCODE_DPAD_LEFT, 14}, {KeyEvent.KEYCODE_DPAD_RIGHT, 15},
        {KeyEvent.KEYCODE_BUTTON_MODE, 16},
    };
    private final Map<Integer, Integer> padSlots = new HashMap<>(); // device id -> pad index
    private final Map<Integer, int[]> padState = new HashMap<>();   // pad index -> {buttons, hatButtons, x, y}

    private static boolean isPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private int[] padFor(InputDevice dev) {
        int id = dev == null ? -1 : dev.getId();
        Integer slot = padSlots.get(id);
        if (slot == null) {
            slot = padSlots.size();
            padSlots.put(id, slot);
            padState.put(slot, new int[4]);
        }
        return padState.get(slot);
    }

    private void sendPad(InputDevice dev, int[] st) {
        int slot = padSlots.get(dev == null ? -1 : dev.getId());
        String name = dev == null ? "Controller" : dev.getName();
        String js = "window.oleanderNativePad&&window.oleanderNativePad(" + slot + "," + (st[0] | st[1]) + ","
            + (st[2] / 100.0) + "," + (st[3] / 100.0) + "," + JSONObject.quote(name) + ")";
        web.evaluateJavascript(js, null);
    }

    private boolean padKey(KeyEvent e) {
        if (!isPad(e.getSource()) && !KeyEvent.isGamepadButton(e.getKeyCode())) return false;
        int bit = -1;
        for (int[] k : PAD_KEYS) if (k[0] == e.getKeyCode()) bit = k[1];
        if (bit < 0) return false;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE || e.getRepeatCount() > 0) return true;
        int[] st = padFor(e.getDevice());
        int before = st[0];
        if (e.getAction() == KeyEvent.ACTION_DOWN) st[0] |= 1 << bit; else st[0] &= ~(1 << bit);
        if (st[0] != before) sendPad(e.getDevice(), st);
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (!isPad(e.getSource()) || e.getAction() != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e);
        int[] st = padFor(e.getDevice());
        int x = Math.round(e.getAxisValue(MotionEvent.AXIS_X) * 100);
        int y = Math.round(e.getAxisValue(MotionEvent.AXIS_Y) * 100);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        int hat = 0;
        if (hy < -0.5f) hat |= 1 << 12;
        if (hy > 0.5f) hat |= 1 << 13;
        if (hx < -0.5f) hat |= 1 << 14;
        if (hx > 0.5f) hat |= 1 << 15;
        if (lt > 0.5f) hat |= 1 << 6;
        if (rt > 0.5f) hat |= 1 << 7;
        // Only report meaningful changes (sticks send a stream of events).
        if (hat != st[1] || Math.abs(x - st[2]) >= 5 || Math.abs(y - st[3]) >= 5
                || (Math.abs(x) < 5) != (Math.abs(st[2]) < 5) || (Math.abs(y) < 5) != (Math.abs(st[3]) < 5)) {
            st[1] = hat;
            st[2] = Math.abs(x) < 5 ? 0 : x;
            st[3] = Math.abs(y) < 5 ? 0 : y;
            sendPad(e.getDevice(), st);
        }
        return true;
    }

    /** The Back button closes dialogs or toggles the in-game menu. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (padKey(e)) return true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("window.oleanderBack ? window.oleanderBack() : false", v -> {
                    if (!"true".equals(v)) moveTaskToBack(true);
                });
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        closeLink();
        web.destroy();
        super.onDestroy();
    }


    // ---- Phone-to-phone link cable (Wi-Fi / hotspot TCP, Bluetooth) ----

    private static final int REQ_BLUETOOTH = 7;
    private static final UUID LINK_UUID = UUID.fromString("6f6c6561-6e64-6572-2d6c-696e6b2d3031");
    private volatile LinkHub link;
    private final ConcurrentLinkedQueue<String> linkInbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean linkFlushPending = new AtomicBoolean();
    private Runnable afterBluetoothPermission;

    private void runJs(String js) {
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private synchronized LinkHub newLink() {
        closeLink();
        link = new LinkHub(new LinkHub.Listener() {
            @Override
            public void onMessage(String line) {
                linkInbox.add(line);
                // Deliver in batches: one script call per UI-thread turn.
                if (linkFlushPending.compareAndSet(false, true)) runOnUiThread(MainActivity.this::flushLink);
            }

            @Override
            public void onStatus(String type, String detail) {
                runJs("window.oleanderLinkStatus&&window.oleanderLinkStatus(" + JSONObject.quote(type) + "," + JSONObject.quote(detail == null ? "" : detail) + ")");
            }
        });
        return link;
    }

    private void flushLink() {
        linkFlushPending.set(false);
        JSONArray batch = new JSONArray();
        String line;
        while ((line = linkInbox.poll()) != null) batch.put(line);
        if (batch.length() > 0) web.evaluateJavascript("window.oleanderLinkRecv&&window.oleanderLinkRecv(" + batch + ")", null);
    }

    private synchronized void closeLink() {
        if (link != null) link.close();
        link = null;
        linkInbox.clear();
    }

    private void linkError(String msg) {
        runJs("window.oleanderLinkStatus&&window.oleanderLinkStatus('error'," + JSONObject.quote(msg) + ")");
    }

    private BluetoothAdapter bluetooth() {
        BluetoothManager m = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** Runs `action` once Bluetooth permission is granted (asks if needed). */
    private void withBluetooth(Runnable action) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            afterBluetoothPermission = action;
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
            return;
        }
        BluetoothAdapter a = bluetooth();
        if (a == null) {
            linkError("This device has no Bluetooth");
            return;
        }
        if (!a.isEnabled()) {
            linkError("Turn on Bluetooth first");
            return;
        }
        action.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_BLUETOOTH) return;
        Runnable next = afterBluetoothPermission;
        afterBluetoothPermission = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && next != null) withBluetooth(next);
        else linkError("Bluetooth permission was denied");
    }

    private static LinkHub.Peer btPeer(BluetoothSocket s) throws java.io.IOException {
        String name;
        try {
            name = s.getRemoteDevice().getName();
        } catch (SecurityException e) {
            name = null;
        }
        return new LinkHub.Peer(s.getInputStream(), s.getOutputStream(), s, name == null ? "Bluetooth device" : name);
    }

    /** An export being written in chunks. */
    private static final class Export {
        final String name;
        final Uri uri;
        final OutputStream out;

        Export(String name, Uri uri, OutputStream out) {
            this.name = name;
            this.uri = uri;
            this.out = out;
        }
    }

    private final Map<Integer, Export> exports = new HashMap<>();
    private int nextExport = 1;

    /** JavaScript bridge (window.OleanderAndroid). */
    private class Bridge {
        @JavascriptInterface
        public String platform() {
            return "android";
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(i, PICK_TREE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No folder picker available", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void rescanFolders() {
            Set<String> t = trees();
            if (!t.isEmpty()) scanTrees(t, true);
        }

        @JavascriptInterface
        public int folderCount() {
            return trees().size();
        }

        @JavascriptInterface
        public void forgetFolders() {
            for (String t : trees()) {
                try {
                    getContentResolver().releasePersistableUriPermission(Uri.parse(t), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TREES).apply();
        }

        @JavascriptInterface
        public boolean createShortcut(String id, String title) {
            if (Build.VERSION.SDK_INT < 26) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Shortcuts need Android 8 or newer", Toast.LENGTH_LONG).show());
                return false;
            }
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Your launcher does not support shortcuts", Toast.LENGTH_LONG).show());
                return false;
            }
            Intent launch = new Intent(MainActivity.this, MainActivity.class);
            launch.setAction(Intent.ACTION_MAIN);
            launch.putExtra("play", id);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ShortcutInfo info = new ShortcutInfo.Builder(MainActivity.this, "game-" + id)
                    .setShortLabel(title.length() > 24 ? title.substring(0, 24) : title)
                    .setLongLabel(title)
                    .setIcon(Icon.createWithResource(MainActivity.this, com.xander.files.R.mipmap.ic_launcher))
                    .setIntent(launch)
                    .build();
            return sm.requestPinShortcut(info, null);
        }

        // ---- link cable ----

        @JavascriptInterface
        public void linkHostWifi() {
            LinkHub hub = newLink();
            new Thread(() -> {
                try {
                    hub.hostTcp(LinkHub.TCP_PORT);
                } catch (Exception e) {
                    linkError("Could not host: " + e.getMessage());
                }
            }, "link-host").start();
        }

        @JavascriptInterface
        public void linkJoinWifi(String host) {
            LinkHub hub = newLink();
            new Thread(() -> {
                try {
                    String h = host.trim();
                    int port = LinkHub.TCP_PORT;
                    int colon = h.lastIndexOf(':');
                    if (colon > 0 && h.indexOf(':') == colon) {
                        port = Integer.parseInt(h.substring(colon + 1));
                        h = h.substring(0, colon);
                    }
                    hub.joinTcp(h, port);
                } catch (Exception e) {
                    linkError("Could not connect to " + host + ": " + e.getMessage());
                }
            }, "link-join").start();
        }

        /** Paired Bluetooth devices, delivered to window.oleanderBtDevices(list). */
        @JavascriptInterface
        public void bluetoothDevices() {
            runOnUiThread(() -> withBluetooth(() -> {
                JSONArray list = new JSONArray();
                try {
                    for (BluetoothDevice d : bluetooth().getBondedDevices()) {
                        JSONObject o = new JSONObject();
                        o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                        o.put("address", d.getAddress());
                        list.put(o);
                    }
                } catch (Exception e) {
                    linkError("Cannot list Bluetooth devices: " + e.getMessage());
                    return;
                }
                runJs("window.oleanderBtDevices&&window.oleanderBtDevices(" + list + ")");
            }));
        }

        @JavascriptInterface
        public void linkHostBluetooth() {
            runOnUiThread(() -> withBluetooth(() -> {
                LinkHub hub = newLink();
                try {
                    BluetoothAdapter a = bluetooth();
                    BluetoothServerSocket server = a.listenUsingInsecureRfcommWithServiceRecord("Oleander link cable", LINK_UUID);
                    String name;
                    try {
                        name = a.getName();
                    } catch (SecurityException e) {
                        name = "this phone";
                    }
                    hub.host(new LinkHub.Acceptor() {
                        @Override
                        public LinkHub.Peer accept() throws java.io.IOException {
                            return btPeer(server.accept());
                        }

                        @Override
                        public void close() throws java.io.IOException {
                            server.close();
                        }
                    }, "Bluetooth (" + name + ")");
                } catch (Exception e) {
                    linkError("Could not host over Bluetooth: " + e.getMessage());
                }
            }));
        }

        @JavascriptInterface
        public void linkJoinBluetooth(String address) {
            runOnUiThread(() -> withBluetooth(() -> {
                LinkHub hub = newLink();
                new Thread(() -> {
                    try {
                        BluetoothDevice d = bluetooth().getRemoteDevice(address);
                        BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(LINK_UUID);
                        s.connect();
                        LinkHub.Peer p = btPeer(s);
                        hub.addPeer(p);
                        runJs("window.oleanderLinkStatus&&window.oleanderLinkStatus('connected'," + JSONObject.quote(p.name) + ")");
                    } catch (Exception e) {
                        linkError("Could not connect over Bluetooth (is Oleander hosting on the other phone?): " + e.getMessage());
                    }
                }, "link-join-bt").start();
            }));
        }

        @JavascriptInterface
        public void linkSend(String line) {
            LinkHub hub = link;
            if (hub != null) hub.send(line);
        }

        @JavascriptInterface
        public void linkClose() {
            // Synchronous: a connect that follows must not be closed by a late close.
            closeLink();
        }

        @JavascriptInterface
        public boolean saveFile(String name, String mime, String base64) {
            int id = saveBegin(name, mime);
            return id >= 0 && saveChunk(id, base64) && saveEnd(id, true);
        }

        /** Starts a chunked export to Downloads/Oleander; returns a handle or -1. */
        @JavascriptInterface
        public int saveBegin(String name, String mime) {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            try {
                OutputStream out;
                Uri uri = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Oleander");
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    out = getContentResolver().openOutputStream(uri);
                } else {
                    File dir = new File(getExternalFilesDir(null), "exports");
                    dir.mkdirs();
                    out = new FileOutputStream(new File(dir, safe));
                }
                synchronized (exports) {
                    int id = nextExport++;
                    exports.put(id, new Export(safe, uri, new java.io.BufferedOutputStream(out, 1 << 20)));
                    return id;
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return -1;
            }
        }

        @JavascriptInterface
        public boolean saveChunk(int id, String base64) {
            Export x;
            synchronized (exports) {
                x = exports.get(id);
            }
            if (x == null) return false;
            try {
                x.out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                saveEnd(id, false);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return false;
            }
        }

        @JavascriptInterface
        public boolean saveEnd(int id, boolean ok) {
            Export x;
            synchronized (exports) {
                x = exports.remove(id);
            }
            if (x == null) return false;
            try {
                x.out.close();
                if (x.uri != null) {
                    if (ok) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(x.uri, v, null, null);
                    } else {
                        getContentResolver().delete(x.uri, null, null);
                    }
                }
                if (ok) runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved to Downloads/Oleander/" + x.name, Toast.LENGTH_SHORT).show());
                return ok;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return false;
            }
        }
    }
}

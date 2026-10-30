package org.telegram.messenger.zentrix.plugins;

import android.content.Context;
import android.widget.Toast;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight Lua 5.2 execution engine using LuaJ.
 * Provides a sandboxed scripting environment with hooks for message interception and event updates.
 * Based on Telegram 12.4.1 / NekoX architecture.
 */
public class LuaPluginsEngine {

    private static final String TAG = "LuaPluginsEngine";
    private static volatile LuaPluginsEngine instance;

    private Globals globals;
    private final Object engineLock = new Object();
    private final List<PluginInfo> loadedPlugins = new CopyOnWriteArrayList<>();

    public static class PluginInfo {
        public final String name;
        public final File file;
        public boolean isEnabled;

        public PluginInfo(String name, File file, boolean isEnabled) {
            this.name = name;
            this.file = file;
            this.isEnabled = isEnabled;
        }
    }

    private LuaPluginsEngine() {
        initSandbox();
    }

    public static LuaPluginsEngine getInstance() {
        if (instance == null) {
            synchronized (LuaPluginsEngine.class) {
                if (instance == null) {
                    instance = new LuaPluginsEngine();
                }
            }
        }
        return instance;
    }

    /**
     * Initializes the Lua 5.2 execution environment with sandboxed globals and registers the 'tg' module.
     */
    private void initSandbox() {
        synchronized (engineLock) {
            try {
                // Initialize standard JSE globals
                globals = JsePlatform.standardGlobals();

                // Apply security restrictions for sandbox safety:
                // Remove direct reflection, OS execution, and unsafe file access
                globals.set("luajava", LuaValue.NIL);
                globals.set("os", LuaValue.NIL);
                globals.set("debug", LuaValue.NIL);
                globals.set("dofile", LuaValue.NIL);
                globals.set("loadfile", LuaValue.NIL);

                // Register Zentrix Telegram module 'tg'
                registerTgModule(globals);

                FileLog.d(TAG + ": Lua sandbox initialized successfully.");
            } catch (Throwable t) {
                FileLog.e(TAG + ": Failed to initialize Lua sandbox", t);
            }
        }
    }

    /**
     * Exposes the 'tg' API module to Lua scripts.
     * Functions:
     * - tg.toast(message)
     * - tg.copy(text)
     * - tg.send(dialogId, text)
     * - tg.log(msg)
     */
    private void registerTgModule(Globals g) {
        LuaTable tg = new LuaTable();

        // 1. tg.toast(message)
        tg.set("toast", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                final String message = arg.tojstring();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Context context = ApplicationLoader.applicationContext;
                        if (context != null) {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable t) {
                        FileLog.e(TAG + ": Error in tg.toast", t);
                    }
                });
                return LuaValue.NIL;
            }
        });

        // 2. tg.copy(text)
        tg.set("copy", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                final String text = arg.tojstring();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        AndroidUtilities.addToClipboard(text);
                    } catch (Throwable t) {
                        FileLog.e(TAG + ": Error in tg.copy", t);
                    }
                });
                return LuaValue.NIL;
            }
        });

        // 3. tg.send(dialogId, text)
        tg.set("send", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                final long dialogId = arg1.tolong();
                final String text = arg2.tojstring();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        int account = UserConfig.selectedAccount;
                        SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
                        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                                text,
                                dialogId,
                                null, // reply_to_msg
                                null, // reply_to_story_item
                                null, // entities
                                null, // webPage
                                true, // notify
                                null, // reply_markup
                                null, // schedule_date
                                null, // schedule_date_random
                                true, // clear_draft
                                0,    // invert_media
                                null, // quick_reply_shortcut
                                false // effect_id
                        );
                        helper.sendMessage(params);
                    } catch (Throwable t) {
                        FileLog.e(TAG + ": Error in tg.send", t);
                    }
                });
                return LuaValue.NIL;
            }
        });

        // 4. tg.log(msg)
        tg.set("log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                final String msg = arg.tojstring();
                FileLog.d("[ZentrixLua] " + msg);
                return LuaValue.NIL;
            }
        });

        // Safe print replacement routing to FileLog
        g.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder("[ZentrixLua] ");
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) sb.append("\t");
                    sb.append(args.arg(i).tojstring());
                }
                FileLog.d(sb.toString());
                return LuaValue.NIL;
            }
        });

        // Expose table
        g.set("tg", tg);
    }

    /**
     * Intercepts outgoing message before transmission.
     * Invokes Lua function: onSendMessage(chatId, text)
     *
     * @param account  Telegram account index
     * @param dialogId Target chat or peer ID
     * @param text     Original message text
     * @return Transformed text, or null if the message should be cancelled.
     */
    public String hookSendMessage(int account, long dialogId, String text) {
        synchronized (engineLock) {
            if (globals == null) {
                return text;
            }

            LuaValue func = globals.get("onSendMessage");
            if (func == null || func.isnil() || !func.isfunction()) {
                // No hook registered, continue normally
                return text;
            }

            try {
                LuaValue argDialogId = LuaValue.valueOf(dialogId);
                LuaValue argText = LuaValue.valueOf(text != null ? text : "");

                LuaValue result = func.call(argDialogId, argText);

                // If Lua returns nil or false, cancel sending
                if (result == null || result.isnil()) {
                    FileLog.d(TAG + ": onSendMessage returned nil -> message cancelled for peer " + dialogId);
                    return null;
                }
                if (result.isboolean()) {
                    if (!result.toboolean()) {
                        FileLog.d(TAG + ": onSendMessage returned false -> message cancelled for peer " + dialogId);
                        return null;
                    }
                    // Returned true, send original text
                    return text;
                }
                if (result.isstring()) {
                    // String returned -> replace message text
                    return result.tojstring();
                }
            } catch (Throwable t) {
                FileLog.e(TAG + ": Error in onSendMessage hook", t);
            }

            return text;
        }
    }

    /**
     * Dispatches an incoming event/update to Lua scripts.
     * Invokes Lua function: onUpdate(event, data)
     *
     * @param eventName Identifier of the update (e.g. "new_message", "dialog_update")
     * @param data      Event payload object
     */
    public void hookOnUpdate(String eventName, Object data) {
        synchronized (engineLock) {
            if (globals == null) {
                return;
            }

            LuaValue func = globals.get("onUpdate");
            if (func == null || func.isnil() || !func.isfunction()) {
                return;
            }

            try {
                LuaValue argEvent = LuaValue.valueOf(eventName != null ? eventName : "");
                LuaValue argData;

                if (data == null) {
                    argData = LuaValue.NIL;
                } else if (data instanceof String) {
                    argData = LuaValue.valueOf((String) data);
                } else if (data instanceof Number) {
                    if (data instanceof Integer || data instanceof Long || data instanceof Short || data instanceof Byte) {
                        argData = LuaValue.valueOf(((Number) data).longValue());
                    } else {
                        argData = LuaValue.valueOf(((Number) data).doubleValue());
                    }
                } else if (data instanceof Boolean) {
                    argData = LuaValue.valueOf((Boolean) data);
                } else {
                    argData = CoerceJavaToLua.coerce(data);
                }

                func.call(argEvent, argData);
            } catch (Throwable t) {
                FileLog.e(TAG + ": Error in onUpdate hook (" + eventName + ")", t);
            }
        }
    }

    /**
     * Executes a Lua script from source string.
     *
     * @param scriptContent Lua source code
     * @param chunkName     Identifier for stack traces / debugging
     * @return true if compilation and execution succeeded
     */
    public boolean loadScript(String scriptContent, String chunkName) {
        synchronized (engineLock) {
            try {
                LuaValue chunk = globals.load(scriptContent, chunkName != null ? chunkName : "script");
                chunk.call();
                FileLog.d(TAG + ": Loaded script chunk: " + chunkName);
                return true;
            } catch (Throwable t) {
                FileLog.e(TAG + ": Failed to load script chunk: " + chunkName, t);
                return false;
            }
        }
    }

    /**
     * Loads and executes a Lua plugin file.
     *
     * @param pluginFile The .lua file on disk
     * @return true if successful
     */
    public boolean loadPlugin(File pluginFile) {
        if (pluginFile == null || !pluginFile.exists() || !pluginFile.isFile()) {
            return false;
        }

        try {
            StringBuilder sb = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(pluginFile), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
            }

            boolean ok = loadScript(sb.toString(), pluginFile.getName());
            if (ok) {
                loadedPlugins.add(new PluginInfo(pluginFile.getName(), pluginFile, true));
            }
            return ok;
        } catch (Throwable t) {
            FileLog.e(TAG + ": Failed to read plugin file: " + pluginFile.getAbsolutePath(), t);
            return false;
        }
    }

    /**
     * Loads all .lua files from the designated directory.
     */
    public void loadPluginsFromDir(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles((d, name) -> name != null && name.toLowerCase().endsWith(".lua"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            loadPlugin(file);
        }
    }

    /**
     * Returns the default directory for Zentrix Lua plugins.
     */
    public static File getPluginsDirectory() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "zentrix_plugins");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Re-initializes the engine and reloads all registered plugins.
     */
    public void reloadPlugins() {
        synchronized (engineLock) {
            initSandbox();
            File pluginsDir = getPluginsDirectory();
            if (pluginsDir != null) {
                loadedPlugins.clear();
                loadPluginsFromDir(pluginsDir);
            }
        }
    }

    /**
     * Returns an unmodifiable list of currently loaded plugins.
     */
    public List<PluginInfo> getLoadedPlugins() {
        return Collections.unmodifiableList(new ArrayList<>(loadedPlugins));
    }
}

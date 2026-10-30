package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.messenger.zentrix.bot.BotAuthHelper;
import org.telegram.messenger.zentrix.plugins.LuaPluginsEngine;
import org.telegram.messenger.zentrix.proxy.ZentrixProxyManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;

/**
 * ZentrixSettingsActivity
 * Comprehensive settings screen for Zentrix mod.
 * Based on Telegram 12.4.1 / NekoX architecture.
 */
public class ZentrixSettingsActivity extends BaseFragment {

    private static final String TAG = "ZentrixSettings";

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;

    // 1. Ghost Mode Section
    private int ghostHeaderRow;
    private int ghostModeRow;
    private int hideReadRow;
    private int hideStoriesRow;
    private int hideTypingRow;
    private int stealthSendingRow;
    private int ghostSectionRow;

    // 2. Anti-Delete & History Section
    private int antiDeleteHeaderRow;
    private int saveDeletedRow;
    private int saveEditedRow;
    private int antiDeleteSectionRow;

    // 3. UI & Customization Section
    private int uiHeaderRow;
    private int classicDrawerRow;
    private int squareStyleRow;
    private int smoothScrollRow;
    private int uiSectionRow;

    // 4. Bot Mode Section
    private int botHeaderRow;
    private int botLoginRow;
    private int botSectionRow;

    // 5. VPN & Proxy Section
    private int vpnHeaderRow;
    private int vpnBypassRow;
    private int vpnConfigRow;
    private int vpnSectionRow;

    // 6. Plugins Section
    private int pluginsHeaderRow;
    private int pluginDirRow;
    private int pluginsSectionRow;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_DETAIL = 2;
    private static final int TYPE_SECTION = 3;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;

        // Ghost Mode Section
        ghostHeaderRow = rowCount++;
        ghostModeRow = rowCount++;
        hideReadRow = rowCount++;
        hideStoriesRow = rowCount++;
        hideTypingRow = rowCount++;
        stealthSendingRow = rowCount++;
        ghostSectionRow = rowCount++;

        // Anti-Delete & History Section
        antiDeleteHeaderRow = rowCount++;
        saveDeletedRow = rowCount++;
        saveEditedRow = rowCount++;
        antiDeleteSectionRow = rowCount++;

        // UI & Customization Section
        uiHeaderRow = rowCount++;
        classicDrawerRow = rowCount++;
        squareStyleRow = rowCount++;
        smoothScrollRow = rowCount++;
        uiSectionRow = rowCount++;

        // Bot Mode Section
        botHeaderRow = rowCount++;
        botLoginRow = rowCount++;
        botSectionRow = rowCount++;

        // VPN & Proxy Section
        vpnHeaderRow = rowCount++;
        vpnBypassRow = rowCount++;
        vpnConfigRow = rowCount++;
        vpnSectionRow = rowCount++;

        // Plugins Section
        pluginsHeaderRow = rowCount++;
        pluginDirRow = rowCount++;
        pluginsSectionRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Zentrix Settings");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position == ghostModeRow) {
                boolean next = !ZentrixConfig.isGhostMode;
                ZentrixConfig.setGhostMode(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == hideReadRow) {
                boolean next = !ZentrixConfig.hideRead;
                ZentrixConfig.setHideRead(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == hideStoriesRow) {
                boolean next = !ZentrixConfig.hideStories;
                ZentrixConfig.setHideStories(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == hideTypingRow) {
                boolean next = !ZentrixConfig.hideTyping;
                ZentrixConfig.setHideTyping(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == stealthSendingRow) {
                boolean next = !ZentrixConfig.useScheduledSending;
                ZentrixConfig.setUseScheduledSending(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == saveDeletedRow) {
                boolean next = !ZentrixConfig.saveDeletedMessages;
                ZentrixConfig.setSaveDeletedMessages(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == saveEditedRow) {
                boolean next = !ZentrixConfig.saveEditedMessages;
                ZentrixConfig.setSaveEditedMessages(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == classicDrawerRow) {
                boolean next = !ZentrixConfig.useClassicDrawer;
                ZentrixConfig.setUseClassicDrawer(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
            } else if (position == squareStyleRow) {
                boolean next = !ZentrixConfig.useSquareStyle;
                ZentrixConfig.setUseSquareStyle(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload);
            } else if (position == smoothScrollRow) {
                boolean next = !ZentrixConfig.smoothScroll;
                ZentrixConfig.setSmoothScroll(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == botLoginRow) {
                showBotTokenLoginDialog();
            } else if (position == vpnBypassRow) {
                boolean next = !ZentrixConfig.vpnEnabled;
                ZentrixProxyManager.setBypassEnabled(next);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (position == vpnConfigRow) {
                showVpnConfigDialog();
            } else if (position == pluginDirRow) {
                copyPluginDirectoryPath();
            }
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Prompts AlertDialog with EditText for entering Telegram Bot Token.
     */
    private void showBotTokenLoginDialog() {
        if (getParentActivity() == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Bot Token Login");
        builder.setMessage("Enter your Telegram Bot Token obtained from @BotFather:");

        final EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setHint("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ");
        if (!TextUtils.isEmpty(ZentrixConfig.botToken)) {
            input.setText(ZentrixConfig.botToken);
            input.setSelection(input.getText().length());
        }
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setBackground(Theme.createEditTextDrawable(getParentActivity(), false));

        FrameLayout container = new FrameLayout(getParentActivity());
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton("Log In", (dialog, which) -> {
            String token = input.getText().toString().trim();
            if (TextUtils.isEmpty(token)) {
                return;
            }

            final AlertDialog progress = new AlertDialog(getParentActivity(), 3);
            progress.show();

            BotAuthHelper.loginWithBotToken(currentAccount, token, new BotAuthHelper.BotAuthCallback() {
                @Override
                public void onSuccess(TLRPC.User user) {
                    progress.dismiss();
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(botLoginRow);
                    }
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), "Logged in as @" + (user != null ? user.username : "bot"), Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(int errorCode, String errorMessage) {
                    progress.dismiss();
                    if (getParentActivity() != null) {
                        new AlertDialog.Builder(getParentActivity())
                                .setTitle("Login Error")
                                .setMessage(errorMessage != null ? errorMessage : "Authentication failed (" + errorCode + ")")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                }
            });
        });

        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    /**
     * Prompts AlertDialog with EditText for entering VLESS or Hysteria 2 configuration link.
     */
    private void showVpnConfigDialog() {
        if (getParentActivity() == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Subscription / Link");
        builder.setMessage("Enter VLESS or Hysteria 2 configuration URI (vless://... or hysteria2://...):");

        final EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setHint("vless://... or hysteria2://...");
        if (!TextUtils.isEmpty(ZentrixConfig.vpnConfigUrl)) {
            input.setText(ZentrixConfig.vpnConfigUrl);
            input.setSelection(input.getText().length());
        }
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setBackground(Theme.createEditTextDrawable(getParentActivity(), false));

        FrameLayout container = new FrameLayout(getParentActivity());
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String url = input.getText().toString().trim();
            ZentrixConfig.setVpnConfigUrl(url);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(vpnConfigRow);
            }
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), "Proxy subscription saved", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    /**
     * Copies Lua plugins directory path to clipboard.
     */
    private void copyPluginDirectoryPath() {
        File dir = LuaPluginsEngine.getPluginsDirectory();
        String path = dir != null ? dir.getAbsolutePath() : "/sdcard/Android/data/org.telegram.messenger/files/zentrix_plugins/";
        AndroidUtilities.addToClipboard(path);
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), "Plugin path copied to clipboard:\n" + path, Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    // RecyclerListView SelectionAdapter
    // ==========================================
    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ghostModeRow
                    || position == hideReadRow
                    || position == hideStoriesRow
                    || position == hideTypingRow
                    || position == stealthSendingRow
                    || position == saveDeletedRow
                    || position == saveEditedRow
                    || position == classicDrawerRow
                    || position == squareStyleRow
                    || position == smoothScrollRow
                    || position == botLoginRow
                    || position == vpnBypassRow
                    || position == vpnConfigRow
                    || position == pluginDirRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ghostHeaderRow
                    || position == antiDeleteHeaderRow
                    || position == uiHeaderRow
                    || position == botHeaderRow
                    || position == vpnHeaderRow
                    || position == pluginsHeaderRow) {
                return TYPE_HEADER;
            } else if (position == ghostSectionRow
                    || position == antiDeleteSectionRow
                    || position == uiSectionRow
                    || position == botSectionRow
                    || position == vpnSectionRow
                    || position == pluginsSectionRow) {
                return TYPE_SECTION;
            } else if (position == botLoginRow
                    || position == vpnConfigRow
                    || position == pluginDirRow) {
                return TYPE_DETAIL;
            } else {
                return TYPE_CHECK;
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    HeaderCell headerCell = new HeaderCell(mContext);
                    headerCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = headerCell;
                    break;
                case TYPE_CHECK:
                    TextCheckCell checkCell = new TextCheckCell(mContext);
                    checkCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = checkCell;
                    break;
                case TYPE_DETAIL:
                    TextDetailSettingsCell detailCell = new TextDetailSettingsCell(mContext);
                    detailCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = detailCell;
                    break;
                case TYPE_SECTION:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == ghostHeaderRow) {
                        cell.setText("Ghost Mode");
                    } else if (position == antiDeleteHeaderRow) {
                        cell.setText("Message History & Anti-Delete");
                    } else if (position == uiHeaderRow) {
                        cell.setText("Interface & Styling");
                    } else if (position == botHeaderRow) {
                        cell.setText("Bot Account Mode");
                    } else if (position == vpnHeaderRow) {
                        cell.setText("Built-in Bypass & VPN (VLESS / Hysteria 2)");
                    } else if (position == pluginsHeaderRow) {
                        cell.setText("Lua Plugins");
                    }
                    break;
                }

                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == ghostModeRow) {
                        cell.setTextAndCheck("Ghost Mode (Master)", ZentrixConfig.isGhostMode, true);
                    } else if (position == hideReadRow) {
                        cell.setTextAndCheck("Hide Read Status", ZentrixConfig.hideRead, true);
                    } else if (position == hideStoriesRow) {
                        cell.setTextAndCheck("Hide Stories Read", ZentrixConfig.hideStories, true);
                    } else if (position == hideTypingRow) {
                        cell.setTextAndCheck("Hide Typing Status", ZentrixConfig.hideTyping, true);
                    } else if (position == stealthSendingRow) {
                        cell.setTextAndCheck("Stealth Offline Sending", ZentrixConfig.useScheduledSending, false);
                    } else if (position == saveDeletedRow) {
                        cell.setTextAndCheck("Save Deleted Messages", ZentrixConfig.saveDeletedMessages, true);
                    } else if (position == saveEditedRow) {
                        cell.setTextAndCheck("Save Edited Messages", ZentrixConfig.saveEditedMessages, false);
                    } else if (position == classicDrawerRow) {
                        cell.setTextAndCheck("Classic Left Drawer", ZentrixConfig.useClassicDrawer, true);
                    } else if (position == squareStyleRow) {
                        cell.setTextAndCheck("Square Avatars & Cards (8dp)", ZentrixConfig.useSquareStyle, true);
                    } else if (position == smoothScrollRow) {
                        cell.setTextAndCheck("Smooth Physics Animations", ZentrixConfig.smoothScroll, false);
                    } else if (position == vpnBypassRow) {
                        cell.setTextAndCheck("Enable Traffic Bypass", ZentrixConfig.vpnEnabled, true);
                    }
                    break;
                }

                case TYPE_DETAIL: {
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    if (position == botLoginRow) {
                        String status = ZentrixConfig.isBotMode ? "Logged in as Bot" : "Not configured";
                        cell.setTextAndValue("Bot Token Login", status, false);
                    } else if (position == vpnConfigRow) {
                        String url = !TextUtils.isEmpty(ZentrixConfig.vpnConfigUrl) ? ZentrixConfig.vpnConfigUrl : "Not configured";
                        cell.setTextAndValue("Subscription / Link", url, false);
                    } else if (position == pluginDirRow) {
                        File dir = LuaPluginsEngine.getPluginsDirectory();
                        String path = dir != null ? dir.getAbsolutePath() : "/sdcard/Android/data/org.telegram.messenger/files/zentrix_plugins/";
                        cell.setTextAndValue("Plugin Directory", path, false);
                    }
                    break;
                }

                case TYPE_SECTION:
                default:
                    break;
            }
        }
    }
}

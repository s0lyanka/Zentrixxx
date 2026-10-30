package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.messenger.zentrix.music.ZentrixMusicManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * ZentrixMusicActivity
 * Dedicated Music Player and Audio Manager screen for Zentrix Telegram mod.
 * Supports Local Audio scan, Saved Messages & Chat music parsing, Now Playing mini-player, and Batch Track Forwarding.
 * Based on Telegram 12.4.1 / NekoX architecture.
 */
public class ZentrixMusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final String TAG = "ZentrixMusicActivity";
    private static final int MENU_BATCH_FORWARD = 1;

    public static final int SOURCE_LOCAL = 0;
    public static final int SOURCE_SAVED = 1;
    public static final int SOURCE_CHAT = 2;

    private int selectedSource = SOURCE_LOCAL;
    private long selectedChatDialogId = 0;
    private long currentLoadedDialogId = 0;

    // UI Components
    private LinearLayout sourceSelectorLayout;
    private TextView tabLocal;
    private TextView tabSaved;
    private TextView tabChat;

    private RecyclerListView listView;
    private TrackAdapter trackAdapter;
    private EmptyTextProgressView emptyView;

    // Bottom Playback Bar
    private FrameLayout bottomPlayerLayout;
    private ImageView bottomThumbView;
    private TextView bottomTitleText;
    private TextView bottomArtistText;
    private ImageView btnPrev;
    private ImageView btnPlayPause;
    private ImageView btnNext;

    // Track Data
    private final ArrayList<MusicTrack> displayedTracks = new ArrayList<>();
    private final ArrayList<MessageObject> currentTelegramTracks = new ArrayList<>();

    // Playback State
    private MusicTrack currentPlayingTrack;
    private boolean isPlaying = false;
    private MediaPlayer localMediaPlayer;

    /**
     * Unified music track item supporting both local device files and Telegram message audio.
     */
    public static class MusicTrack {
        public final boolean isLocal;
        public final ZentrixMusicManager.LocalTrack localTrack;
        public final MessageObject messageObject;
        public final String title;
        public final String artist;
        public final long durationSec;

        public MusicTrack(ZentrixMusicManager.LocalTrack local) {
            this.isLocal = true;
            this.localTrack = local;
            this.messageObject = null;
            this.title = !TextUtils.isEmpty(local.title) ? local.title : "Unknown Track";
            this.artist = !TextUtils.isEmpty(local.artist) ? local.artist : "Unknown Artist";
            this.durationSec = local.duration > 0 ? (local.duration / 1000) : 0;
        }

        public MusicTrack(MessageObject msg) {
            this.isLocal = false;
            this.localTrack = null;
            this.messageObject = msg;
            String msgTitle = msg != null ? msg.getMusicTitle() : null;
            String msgArtist = msg != null ? msg.getMusicAuthor() : null;
            this.title = !TextUtils.isEmpty(msgTitle) ? msgTitle : "Audio Track";
            this.artist = !TextUtils.isEmpty(msgArtist) ? msgArtist : "Telegram Audio";
            this.durationSec = msg != null ? (long) msg.getDuration() : 0;
        }

        public String getFormattedDuration() {
            if (durationSec <= 0) {
                return "--:--";
            }
            return String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.mediaDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        stopLocalMediaPlayer();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.mediaDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
    }

    @Override
    public View createView(Context context) {
        // 1. ActionBar configuration
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Zentrix Music Player");

        ActionBarMenu menu = actionBar.createMenu();
        // Batch forward icon
        ActionBarMenuItem forwardItem = menu.addItem(MENU_BATCH_FORWARD, R.drawable.msg_forward);
        if (forwardItem != null) {
            forwardItem.setContentDescription("Batch Forward");
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_BATCH_FORWARD) {
                    openBatchForwardChatPicker();
                }
            }
        });

        // 2. Root FrameLayout
        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = rootLayout;

        LinearLayout mainContentLayout = new LinearLayout(context);
        mainContentLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(mainContentLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.START, 0, 0, 0, 64));

        // 3. Source Selector Segment
        createSourceSelector(context, mainContentLayout);

        // 4. Track List View & Empty View Container
        FrameLayout listContainer = new FrameLayout(context);
        mainContentLayout.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(true);
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText("No music tracks found");
        emptyView.showTextView();
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setEmptyView(emptyView);

        trackAdapter = new TrackAdapter(context);
        listView.setAdapter(trackAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < displayedTracks.size()) {
                playTrack(displayedTracks.get(position));
            }
        });

        // 5. Bottom 'Now Playing' mini player
        createBottomPlayer(context, rootLayout);

        // Initial track loading
        selectSource(SOURCE_LOCAL);

        return fragmentView;
    }

    /**
     * Constructs the source selector bar (Local Audio / Saved Messages / Select Chat...).
     */
    private void createSourceSelector(Context context, LinearLayout parent) {
        sourceSelectorLayout = new LinearLayout(context);
        sourceSelectorLayout.setOrientation(LinearLayout.HORIZONTAL);
        sourceSelectorLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        sourceSelectorLayout.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));

        tabLocal = createSegmentTab(context, "Local Audio", () -> selectSource(SOURCE_LOCAL));
        tabSaved = createSegmentTab(context, "Saved Messages", () -> selectSource(SOURCE_SAVED));
        tabChat = createSegmentTab(context, "Select Chat...", this::openChatPickerForSource);

        sourceSelectorLayout.addView(tabLocal, LayoutHelper.createLinear(0, 36, 1.0f, 0, 0, 4, 0));
        sourceSelectorLayout.addView(tabSaved, LayoutHelper.createLinear(0, 36, 1.0f, 4, 0, 4, 0));
        sourceSelectorLayout.addView(tabChat, LayoutHelper.createLinear(0, 36, 1.0f, 4, 0, 0, 0));

        parent.addView(sourceSelectorLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Subtle divider below tabs
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        parent.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
    }

    private TextView createSegmentTab(Context context, String title, Runnable onClick) {
        TextView tab = new TextView(context);
        tab.setText(title);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tab.setGravity(Gravity.CENTER);
        tab.setSingleLine(true);
        tab.setEllipsize(TextUtils.TruncateAt.END);
        tab.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        tab.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.run();
            }
        });
        return tab;
    }

    private void updateSourceTabs(String chatTitle) {
        int activeBg = Theme.getColor(Theme.key_featuredStickers_addButton);
        int activeText = Theme.getColor(Theme.key_featuredStickers_buttonText);
        int inactiveText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);

        applyTabStyle(tabLocal, selectedSource == SOURCE_LOCAL, activeBg, activeText, inactiveText);
        applyTabStyle(tabSaved, selectedSource == SOURCE_SAVED, activeBg, activeText, inactiveText);
        applyTabStyle(tabChat, selectedSource == SOURCE_CHAT, activeBg, activeText, inactiveText);

        if (selectedSource == SOURCE_CHAT && !TextUtils.isEmpty(chatTitle)) {
            tabChat.setText(chatTitle);
        } else {
            tabChat.setText("Select Chat...");
        }
    }

    private void applyTabStyle(TextView tab, boolean active, int activeBg, int activeText, int inactiveText) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(AndroidUtilities.dp(18));
        if (active) {
            gd.setColor(activeBg);
            tab.setTextColor(activeText);
        } else {
            gd.setColor(Color.TRANSPARENT);
            tab.setTextColor(inactiveText);
        }
        tab.setBackground(gd);
    }

    /**
     * Switches the current track source and triggers data loading.
     */
    private void selectSource(int source) {
        selectedSource = source;
        updateSourceTabs(null);

        if (source == SOURCE_LOCAL) {
            loadLocalAudio();
        } else if (source == SOURCE_SAVED) {
            long savedUserId = UserConfig.getInstance(currentAccount).getClientUserId();
            loadChatTracks(savedUserId, "Saved Messages");
        }
    }

    /**
     * Scans local storage for audio tracks via MediaStore.
     */
    private void loadLocalAudio() {
        currentTelegramTracks.clear();
        displayedTracks.clear();
        if (trackAdapter != null) {
            trackAdapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setText("Scanning local music files...");
            emptyView.showProgress();
        }

        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<ZentrixMusicManager.LocalTrack> locals = ZentrixMusicManager.loadLocalTracks();
            AndroidUtilities.runOnUIThread(() -> {
                displayedTracks.clear();
                for (ZentrixMusicManager.LocalTrack lt : locals) {
                    displayedTracks.add(new MusicTrack(lt));
                }
                if (trackAdapter != null) {
                    trackAdapter.notifyDataSetChanged();
                }
                if (emptyView != null) {
                    if (displayedTracks.isEmpty()) {
                        emptyView.setText("No local audio files found on device");
                        emptyView.showTextView();
                    } else {
                        emptyView.setVisibility(View.GONE);
                    }
                }
            });
        });
    }

    /**
     * Loads audio tracks from a Telegram chat / Saved Messages.
     */
    private void loadChatTracks(long dialogId, String chatTitle) {
        currentLoadedDialogId = dialogId;
        currentTelegramTracks.clear();
        displayedTracks.clear();
        if (trackAdapter != null) {
            trackAdapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setText("Loading music from " + chatTitle + "...");
            emptyView.showProgress();
        }

        // 1. Dispatch load request via ZentrixMusicManager
        ZentrixMusicManager.loadTracksFromChat(currentAccount, dialogId, 100, tracks -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (tracks != null && !tracks.isEmpty()) {
                    onTelegramTracksLoaded(tracks);
                }
            });
        });

    }

    private void onTelegramTracksLoaded(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            if (displayedTracks.isEmpty() && emptyView != null) {
                emptyView.setText("No music tracks found in this chat");
                emptyView.showTextView();
            }
            return;
        }

        currentTelegramTracks.clear();
        displayedTracks.clear();
        for (MessageObject msg : messages) {
            if (msg != null && (msg.isMusic() || msg.isVoice())) {
                currentTelegramTracks.add(msg);
                displayedTracks.add(new MusicTrack(msg));
            }
        }

        if (trackAdapter != null) {
            trackAdapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            if (displayedTracks.isEmpty()) {
                emptyView.setText("No music tracks found in this chat");
                emptyView.showTextView();
            } else {
                emptyView.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Launches DialogsActivity picker to select a chat for loading music.
     */
    private void openChatPickerForSource() {
        if (getParentActivity() == null) {
            return;
        }

        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("cantSendToBigGroups", false);
        args.putInt("dialogsType", 3);

        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((dialogsActivity, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return true;
            }
            long chatDialogId = dids.get(0).dialogId;
            dialogsActivity.finishFragment();
            selectedChatDialogId = chatDialogId;

            TLRPC.Chat chat = getMessagesController().getChat(-chatDialogId);
            TLRPC.User user = getMessagesController().getUser(chatDialogId);
            String title = chat != null ? chat.title : (user != null ? UserObject.getUserName(user) : "Selected Chat");

            selectedSource = SOURCE_CHAT;
            updateSourceTabs(title);
            loadChatTracks(chatDialogId, title);
            return true;
        });
        presentFragment(fragment);
    }

    /**
     * Launches DialogsActivity picker to select target chat for batch track forwarding.
     */
    private void openBatchForwardChatPicker() {
        if (getParentActivity() == null) {
            return;
        }

        if (currentTelegramTracks == null || currentTelegramTracks.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Batch Forward");
            builder.setMessage("There are no Telegram audio tracks loaded to forward.\n\nBatch forwarding requires Telegram audio messages (from Saved Messages or a selected chat). Switch to 'Saved Messages' or 'Select Chat...' to load tracks.");
            builder.setPositiveButton("OK", null);
            showDialog(builder.create());
            return;
        }

        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("cantSendToBigGroups", false);
        args.putInt("dialogsType", 3);

        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((dialogsActivity, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return true;
            }
            long targetDialogId = dids.get(0).dialogId;
            dialogsActivity.finishFragment();
            startBatchForward(targetDialogId);
            return true;
        });
        presentFragment(fragment);
    }

    /**
     * Initiates batch forwarding to target chat dialog with a progress dialog.
     */
    private void startBatchForward(long targetDialogId) {
        if (getParentActivity() == null || currentTelegramTracks == null || currentTelegramTracks.isEmpty()) {
            return;
        }

        final int total = currentTelegramTracks.size();
        final AlertDialog progress = new AlertDialog(getParentActivity(), 3);
        progress.setTitle("Forwarding Music");
        progress.setMessage("Forwarding 0 / " + total + " tracks...");
        progress.setCanceledOnTouchOutside(false);
        progress.setCancelable(false);
        showDialog(progress);

        ZentrixMusicManager.forwardAllAudio(currentAccount, new ArrayList<>(currentTelegramTracks), targetDialogId, new ZentrixMusicManager.ForwardCallback() {
            @Override
            public void onProgress(int forwarded, int totalCount) {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.setMessage("Forwarding " + forwarded + " / " + totalCount + " tracks...");
                });
            }

            @Override
            public void onComplete() {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), "Successfully forwarded " + total + " tracks!", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    // ==========================================
    // Playback Controller & Mini Player
    // ==========================================
    private void createBottomPlayer(Context context, FrameLayout parent) {
        bottomPlayerLayout = new FrameLayout(context);
        bottomPlayerLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bottomPlayerLayout.setVisibility(View.GONE);

        // Top subtle divider
        View topDivider = new View(context);
        topDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        bottomPlayerLayout.addView(topDivider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.TOP));

        // Thumbnail / Music note icon
        bottomThumbView = new ImageView(context);
        bottomThumbView.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setCornerRadius(AndroidUtilities.dp(8));
        thumbBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        bottomThumbView.setBackground(thumbBg);
        bottomThumbView.setImageDrawable(createMusicNoteDrawable(context, Theme.getColor(Theme.key_featuredStickers_buttonText)));
        bottomPlayerLayout.addView(bottomThumbView, LayoutHelper.createFrame(40, 40, Gravity.START | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        // Text Info (Title & Artist)
        LinearLayout infoLayout = new LinearLayout(context);
        infoLayout.setOrientation(LinearLayout.VERTICAL);
        infoLayout.setGravity(Gravity.CENTER_VERTICAL);

        bottomTitleText = new TextView(context);
        bottomTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bottomTitleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bottomTitleText.setTypeface(AndroidUtilities.bold());
        bottomTitleText.setSingleLine(true);
        bottomTitleText.setEllipsize(TextUtils.TruncateAt.END);
        infoLayout.addView(bottomTitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        bottomArtistText = new TextView(context);
        bottomArtistText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        bottomArtistText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        bottomArtistText.setSingleLine(true);
        bottomArtistText.setEllipsize(TextUtils.TruncateAt.END);
        infoLayout.addView(bottomArtistText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        bottomPlayerLayout.addView(infoLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL, 64, 0, 140, 0));

        // Media Controls (Previous, Play/Pause, Next)
        LinearLayout controlsLayout = new LinearLayout(context);
        controlsLayout.setOrientation(LinearLayout.HORIZONTAL);
        controlsLayout.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        int iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

        btnPrev = new ImageView(context);
        btnPrev.setScaleType(ImageView.ScaleType.CENTER);
        btnPrev.setImageDrawable(createPrevDrawable(context, iconColor));
        btnPrev.setBackground(Theme.createSelectorWithBackgroundDrawable(0, Theme.getColor(Theme.key_listSelector)));
        btnPrev.setOnClickListener(v -> playPreviousTrack());
        controlsLayout.addView(btnPrev, LayoutHelper.createLinear(36, 36, 0, 0, 4, 0));

        btnPlayPause = new ImageView(context);
        btnPlayPause.setScaleType(ImageView.ScaleType.CENTER);
        btnPlayPause.setImageDrawable(createPlayDrawable(context, iconColor));
        btnPlayPause.setBackground(Theme.createSelectorWithBackgroundDrawable(0, Theme.getColor(Theme.key_listSelector)));
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        controlsLayout.addView(btnPlayPause, LayoutHelper.createLinear(40, 40, 4, 0, 4, 0));

        btnNext = new ImageView(context);
        btnNext.setScaleType(ImageView.ScaleType.CENTER);
        btnNext.setImageDrawable(createNextDrawable(context, iconColor));
        btnNext.setBackground(Theme.createSelectorWithBackgroundDrawable(0, Theme.getColor(Theme.key_listSelector)));
        btnNext.setOnClickListener(v -> playNextTrack());
        controlsLayout.addView(btnNext, LayoutHelper.createLinear(36, 36, 4, 0, 10, 0));

        bottomPlayerLayout.addView(controlsLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL));

        parent.addView(bottomPlayerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM));
    }

    private void playTrack(MusicTrack track) {
        if (track == null) {
            return;
        }

        currentPlayingTrack = track;
        bottomPlayerLayout.setVisibility(View.VISIBLE);
        bottomTitleText.setText(track.title);
        bottomArtistText.setText(track.artist);

        if (track.isLocal) {
            // Stop Telegram audio playback if active
            if (MediaController.getInstance().isPlayingMessage(MediaController.getInstance().getPlayingMessageObject())) {
                MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
            }
            playLocalTrack(track.localTrack);
        } else {
            // Stop local MediaPlayer if active
            stopLocalMediaPlayer();
            MediaController.getInstance().playMessage(track.messageObject);
            isPlaying = true;
            updatePlayPauseState();
        }
    }

    private void playLocalTrack(ZentrixMusicManager.LocalTrack local) {
        if (local == null || TextUtils.isEmpty(local.path)) {
            return;
        }

        try {
            if (localMediaPlayer == null) {
                localMediaPlayer = new MediaPlayer();
                localMediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    updatePlayPauseState();
                    if (ZentrixConfig.autoPlayNext) {
                        playNextTrack();
                    }
                });
            } else {
                localMediaPlayer.reset();
            }

            localMediaPlayer.setDataSource(local.path);
            localMediaPlayer.prepare();
            localMediaPlayer.start();
            isPlaying = true;
            updatePlayPauseState();
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to play local audio track", e);
            isPlaying = false;
            updatePlayPauseState();
        }
    }

    private void togglePlayPause() {
        if (currentPlayingTrack == null) {
            if (!displayedTracks.isEmpty()) {
                playTrack(displayedTracks.get(0));
            }
            return;
        }

        if (currentPlayingTrack.isLocal) {
            if (localMediaPlayer != null) {
                if (localMediaPlayer.isPlaying()) {
                    localMediaPlayer.pause();
                    isPlaying = false;
                } else {
                    localMediaPlayer.start();
                    isPlaying = true;
                }
                updatePlayPauseState();
            }
        } else {
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(currentPlayingTrack.messageObject);
                isPlaying = true;
            } else {
                MediaController.getInstance().pauseMessage(currentPlayingTrack.messageObject);
                isPlaying = false;
            }
            updatePlayPauseState();
        }
    }

    private void playNextTrack() {
        if (displayedTracks.isEmpty() || currentPlayingTrack == null) {
            return;
        }
        int index = displayedTracks.indexOf(currentPlayingTrack);
        if (index != -1 && index + 1 < displayedTracks.size()) {
            playTrack(displayedTracks.get(index + 1));
        } else if (!displayedTracks.isEmpty()) {
            playTrack(displayedTracks.get(0));
        }
    }

    private void playPreviousTrack() {
        if (displayedTracks.isEmpty() || currentPlayingTrack == null) {
            return;
        }
        int index = displayedTracks.indexOf(currentPlayingTrack);
        if (index > 0) {
            playTrack(displayedTracks.get(index - 1));
        } else if (!displayedTracks.isEmpty()) {
            playTrack(displayedTracks.get(displayedTracks.size() - 1));
        }
    }

    private void updatePlayPauseState() {
        int iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        if (isPlaying) {
            btnPlayPause.setImageDrawable(createPauseDrawable(getParentActivity(), iconColor));
        } else {
            btnPlayPause.setImageDrawable(createPlayDrawable(getParentActivity(), iconColor));
        }
    }

    private void stopLocalMediaPlayer() {
        if (localMediaPlayer != null) {
            try {
                if (localMediaPlayer.isPlaying()) {
                    localMediaPlayer.stop();
                }
                localMediaPlayer.release();
            } catch (Exception ignored) {
            }
            localMediaPlayer = null;
        }
    }

    // ==========================================
    // NotificationCenter Delegate
    // ==========================================
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mediaDidLoad) {
            long uid = (Long) args[0];
            int type = (Integer) args[5];
            if (uid == currentLoadedDialogId && type == MediaDataController.MEDIA_MUSIC) {
                ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
                onTelegramTracksLoaded(messages);
            }
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidReset) {
            if (currentPlayingTrack != null && !currentPlayingTrack.isLocal) {
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                isPlaying = playing != null && !MediaController.getInstance().isMessagePaused();
                updatePlayPauseState();
            }
        }
    }

    // ==========================================
    // RecyclerListView Adapter & TrackCell
    // ==========================================
    private class TrackAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public TrackAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return displayedTracks.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TrackCell cell = new TrackCell(mContext);
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TrackCell cell = (TrackCell) holder.itemView;
            MusicTrack track = displayedTracks.get(position);
            boolean divider = position < displayedTracks.size() - 1;
            cell.setTrack(track, divider);
        }
    }

    /**
     * Custom Track Cell rendering Title, Artist, Duration and thumbnail.
     */
    public static class TrackCell extends FrameLayout {

        private final ImageView iconView;
        private final TextView titleTextView;
        private final TextView artistTextView;
        private final TextView durationTextView;
        private final View dividerView;

        public TrackCell(Context context) {
            super(context);
            setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));

            // Left music icon thumbnail
            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setCornerRadius(AndroidUtilities.dp(8));
            iconBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            iconView.setBackground(iconBg);
            iconView.setImageDrawable(createMusicNoteDrawable(context, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2)));
            addView(iconView, LayoutHelper.createFrame(40, 40, Gravity.START | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            // Title
            titleTextView = new TextView(context);
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setSingleLine(true);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.TOP, 66, 10, 68, 0));

            // Artist
            artistTextView = new TextView(context);
            artistTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            artistTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            artistTextView.setSingleLine(true);
            artistTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(artistTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.TOP, 66, 31, 68, 0));

            // Duration
            durationTextView = new TextView(context);
            durationTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            durationTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            durationTextView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            addView(durationTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

            // Bottom Divider
            dividerView = new View(context);
            dividerView.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(dividerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM, 66, 0, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(58), MeasureSpec.EXACTLY));
        }

        public void setTrack(MusicTrack track, boolean divider) {
            titleTextView.setText(track.title);
            artistTextView.setText(track.artist);
            durationTextView.setText(track.getFormattedDuration());
            dividerView.setVisibility(divider ? VISIBLE : GONE);
        }
    }

    // ==========================================
    // Vector & Canvas Drawables (Zero Asset Crash Safety)
    // ==========================================
    private static Drawable createPlayDrawable(Context context, int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            {
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                float w = getBounds().width();
                float h = getBounds().height();
                float cx = w / 2.0f;
                float cy = h / 2.0f;
                float r = Math.min(w, h) * 0.28f;

                path.reset();
                path.moveTo(cx - r * 0.7f, cy - r);
                path.lineTo(cx + r * 1.1f, cy);
                path.lineTo(cx - r * 0.7f, cy + r);
                path.close();
                canvas.drawPath(path, paint);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }

    private static Drawable createPauseDrawable(Context context, int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                float w = getBounds().width();
                float h = getBounds().height();
                float cx = w / 2.0f;
                float cy = h / 2.0f;
                float barWidth = AndroidUtilities.dp(3.5f);
                float barHeight = AndroidUtilities.dp(14);
                float gap = AndroidUtilities.dp(3.5f);

                canvas.drawRect(cx - gap - barWidth, cy - barHeight / 2.0f, cx - gap, cy + barHeight / 2.0f, paint);
                canvas.drawRect(cx + gap, cy - barHeight / 2.0f, cx + gap + barWidth, cy + barHeight / 2.0f, paint);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }

    private static Drawable createNextDrawable(Context context, int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            {
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                float w = getBounds().width();
                float h = getBounds().height();
                float cx = w / 2.0f;
                float cy = h / 2.0f;
                float r = Math.min(w, h) * 0.22f;

                path.reset();
                path.moveTo(cx - r, cy - r);
                path.lineTo(cx + r * 0.3f, cy);
                path.lineTo(cx - r, cy + r);
                path.close();
                canvas.drawPath(path, paint);

                float barW = AndroidUtilities.dp(2);
                canvas.drawRect(cx + r * 0.5f, cy - r, cx + r * 0.5f + barW, cy + r, paint);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }

    private static Drawable createPrevDrawable(Context context, int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            {
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                float w = getBounds().width();
                float h = getBounds().height();
                float cx = w / 2.0f;
                float cy = h / 2.0f;
                float r = Math.min(w, h) * 0.22f;

                path.reset();
                path.moveTo(cx + r, cy - r);
                path.lineTo(cx - r * 0.3f, cy);
                path.lineTo(cx + r, cy + r);
                path.close();
                canvas.drawPath(path, paint);

                float barW = AndroidUtilities.dp(2);
                canvas.drawRect(cx - r * 0.5f - barW, cy - r, cx - r * 0.5f, cy + r, paint);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }

    private static Drawable createMusicNoteDrawable(Context context, int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                float w = getBounds().width();
                float h = getBounds().height();
                float cx = w / 2.0f;
                float cy = h / 2.0f;

                // Simple stylized musical note
                float noteRadius = AndroidUtilities.dp(3.5f);
                canvas.drawCircle(cx - AndroidUtilities.dp(3), cy + AndroidUtilities.dp(4), noteRadius, paint);
                canvas.drawRect(cx - AndroidUtilities.dp(0.5f), cy - AndroidUtilities.dp(6), cx + AndroidUtilities.dp(1.5f), cy + AndroidUtilities.dp(4), paint);
                canvas.drawRect(cx - AndroidUtilities.dp(0.5f), cy - AndroidUtilities.dp(6), cx + AndroidUtilities.dp(5), cy - AndroidUtilities.dp(3), paint);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }
}

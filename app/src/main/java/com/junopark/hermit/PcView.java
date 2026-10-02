package com.junopark.hermit;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.junopark.hermit.binding.PlatformBinding;
import com.junopark.hermit.binding.crypto.AndroidCryptoProvider;
import com.junopark.hermit.computers.ComputerManagerListener;
import com.junopark.hermit.computers.ComputerManagerService;
import com.junopark.hermit.grid.PcGridAdapter;
import com.junopark.hermit.grid.assets.DiskAssetLoader;
import com.junopark.hermit.hermit.CrashReporter;
import com.junopark.hermit.hermit.RemoteShutdown;
import com.junopark.hermit.hermit.SessionSummary;
import com.junopark.hermit.hermit.ShellPairingPage;
import com.junopark.hermit.nvstream.http.ComputerDetails;
import com.junopark.hermit.nvstream.http.HostHttpResponseException;
import com.junopark.hermit.nvstream.http.NvApp;
import com.junopark.hermit.nvstream.http.NvHTTP;
import com.junopark.hermit.nvstream.http.PairingManager;
import com.junopark.hermit.nvstream.http.PairingManager.PairState;
import com.junopark.hermit.nvstream.wol.WakeOnLanSender;
import com.junopark.hermit.preferences.AddComputerManually;
import com.junopark.hermit.preferences.GlPreferences;
import com.junopark.hermit.preferences.PreferenceConfiguration;
import com.junopark.hermit.preferences.StreamSettings;
import com.junopark.hermit.ui.AdapterFragment;
import com.junopark.hermit.ui.AdapterFragmentCallbacks;
import com.junopark.hermit.utils.Dialog;
import com.junopark.hermit.utils.HelpLauncher;
import com.junopark.hermit.utils.ServerHelper;
import com.junopark.hermit.utils.ShortcutHelper;
import com.junopark.hermit.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import com.junopark.hermit.hermit.HermitNotice;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    // Hermit: a View, since the layout overrides decide its class (a LinearLayout in nonRoot)
    private View noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    // Hermit: the PIN dialog of the pairing in progress
    private AlertDialog pairingDialog;
    // The pairing attempt the dialog belongs to; a cancelled attempt that ends late leaves a
    // newer attempt's dialog and polling alone
    private Object pairingAttempt;
    // Counts this device's pairing attempts, so withdrawing a cancelled one can tell that a newer
    // one has replaced it on the host (static: a recreated activity may start the next attempt)
    private static final java.util.concurrent.atomic.AtomicInteger pairingGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    // Held while a cancelled attempt is withdrawn; a newer attempt takes it before it asks the host
    // to pair, so the withdrawal (same uniqueid) can't reach the host after it and drop it
    private static final Object pairingWithdrawLock = new Object();
    // Shell closes a pairing request that got no PIN after 300 s, and Hermit stops waiting at 295 s
    // (NvHTTP); a connection error after this long, while getservercert still waits for its
    // answer, means the PIN window ran out
    private static final long PAIRING_PIN_WINDOW_MS = 290_000;
    // A PC paired while this activity was in the background (the Shell pairing page in the
    // browser): its app list opens from onResume(), since a background launch is blocked
    private ComputerDetails pendingAppList;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int RESTART_PC_ID = 12;
    private final static int SHUTDOWN_PC_ID = 13;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    HermitLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            HermitLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        dismissPairingDialog();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        // Hermit: details of an app crash from the last run, and the summary of a stream that
        // was started from here (Resume Session)
        CrashReporter.showIfPending(this);
        SessionSummary.showIfPending(this);

        inForeground = true;
        startComputerUpdates();

        if (pendingAppList != null) {
            ComputerDetails computer = pendingAppList;
            pendingAppList = null;
            doAppList(computer, true, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
            // Hermit: turning the host off or restarting it (Shell); last, away from the rest
            menu.add(Menu.NONE, RESTART_PC_ID, 8, getResources().getString(R.string.hermit_power_menu_restart));
            menu.add(Menu.NONE, SHUTDOWN_PC_ID, 9, getResources().getString(R.string.hermit_power_menu_shutdown));
        }

        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.pair_pc_offline), HermitNotice.SHORT);
            return;
        }
        if (managerBinder == null) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
            return;
        }

        // Hermit: no "Pairing" toast; the PIN dialog shows that pairing is under way
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final Object attempt = new Object();
        final int generation = pairingGeneration.incrementAndGet();
        final String uniqueId = NvHTTP.pairingUniqueId(computer, managerBinder.getUniqueId());
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                long pairingStartedAt = 0;
                PairingManager pm = null;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, uniqueId, computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();
                        final NvHTTP pairingConn = httpConn;

                        // Hermit: a PIN dialog that stays up until pairing ends; Cancel aborts it
                        runOnUiThread(() -> showPairingDialog(computer, pinStr, attempt, () -> {
                            cancelled.set(true);
                            new Thread(() -> {
                                pairingConn.cancelPendingRequests();
                                withdrawPairing(computer, uniqueId, generation);
                            }).start();
                        }));

                        pm = httpConn.getPairingManager();

                        synchronized (pairingWithdrawLock) {
                            // Waits for an earlier, cancelled attempt's withdrawal to be sent
                        }

                        String serverInfo = httpConn.getServerInfo(true);
                        pairingStartedAt = SystemClock.elapsedRealtime();
                        PairState pairState = pm.pair(serverInfo, pinStr);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = ServerHelper.describeHostError(PcView.this, e);
                    // Only while getservercert was waiting for the PIN: a later step failing is a real error
                    if (e instanceof IOException && !(e instanceof HostHttpResponseException) && pairingStartedAt != 0
                            && !pm.isServerCertAnswered()
                            && SystemClock.elapsedRealtime() - pairingStartedAt >= PAIRING_PIN_WINDOW_MS) {
                        message = getResources().getString(R.string.hermit_pair_pin_timeout);
                        if (!cancelled.get()) {
                            // Hermit gave up just before the host does: a PIN entered in between
                            // must not reach the abandoned attempt (Cancel already withdrew it)
                            withdrawPairing(computer, uniqueId, generation);
                        }
                    }
                }

                if (cancelled.get() && !success) {
                    // The aborted request fails with an IOException: not an error to show
                    message = getResources().getString(R.string.hermit_pair_cancelled);
                }

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (pairingAttempt != null && pairingAttempt != attempt) {
                            // A newer pairing is under way: its dialog and polling are its own
                            return;
                        }
                        dismissPairingDialog();
                        if (cancelled.get() && toastSuccess) {
                            // Paired after all (the PIN was entered just before Cancel)
                            startComputerUpdates();
                            return;
                        }
                        if (toastMessage != null) {
                            HermitNotice.show(PcView.this, toastMessage, HermitNotice.LONG);
                        }

                        if (toastSuccess && !inForeground) {
                            // Paired from the browser (Shell pairing page): Android blocks an
                            // activity launch from the background, so the app list opens when
                            // the user comes back
                            pendingAppList = computer;
                            startComputerUpdates();
                        }
                        else if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    // Hermit: aborting the request leaves the attempt waiting on the host, which could still give
    // it a PIN entered in the web UI: /unpair with this device's ID drops it. Best effort, off the
    // UI thread; skipped once a newer attempt from this device has replaced it on the host.
    private void withdrawPairing(ComputerDetails computer, String uniqueId, int generation) {
        if (computer.nvidiaServer) {
            // GameStream pairs under the shared ID (NvHTTP.pairingUniqueId()), so its /unpair would
            // unpair every client using that ID, not only this attempt
            return;
        }
        synchronized (pairingWithdrawLock) {
            if (pairingGeneration.get() != generation) {
                return;
            }
            try {
                // A fresh connection: the cancelled one fails every request at once
                new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(PcView.this))
                        .withdrawPairing();
            } catch (IOException e) {
                HermitLog.warning("Withdrawing the cancelled pairing failed: "+e.getMessage());
            }
        }
    }

    // Hermit: the PIN large on its own line, the Shell hint, a button that opens the host's web UI
    // pairing page with the PIN filled in (open-source hosts), and one Cancel button (also back)
    private void showPairingDialog(ComputerDetails computer, String pin, Object attempt, Runnable onCancel) {
        if (isFinishing()) {
            return;
        }
        dismissPairingDialog();
        pairingAttempt = attempt;

        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * density);
        content.setPadding(pad, Math.round(8 * density), pad, 0);

        TextView pinView = new TextView(this);
        pinView.setText(pin);
        pinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        pinView.setLetterSpacing(0.2f);
        pinView.setFontFeatureSettings("tnum");
        pinView.setGravity(Gravity.CENTER);
        pinView.setTextColor(getResources().getColor(R.color.hermit_accent));
        pinView.setTextIsSelectable(true);
        content.addView(pinView);

        TextView help = new TextView(this);
        help.setText(R.string.pair_pairing_help);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        help.setTextColor(getResources().getColor(R.color.hermit_text_secondary));
        help.setPadding(0, Math.round(12 * density), 0, 0);
        content.addView(help);

        if (ShellPairingPage.isAvailable(computer)) {
            Button openPage = new Button(this);
            openPage.setText(R.string.hermit_pair_open_web);
            openPage.setOnClickListener(v -> ShellPairingPage.open(PcView.this, computer, pin));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.topMargin = Math.round(12 * density);
            content.addView(openPage, params);

            TextView webHelp = new TextView(this);
            webHelp.setText(R.string.hermit_pair_open_web_help);
            webHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            webHelp.setTextColor(getResources().getColor(R.color.hermit_text_secondary));
            webHelp.setPadding(0, Math.round(4 * density), 0, 0);
            content.addView(webHelp);
        }

        pairingDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pair_pairing_title)
                .setView(content)
                .setNegativeButton(R.string.hermit_action_cancel, (d, w) -> onCancel.run())
                .setOnCancelListener(d -> onCancel.run())
                .create();
        pairingDialog.setCanceledOnTouchOutside(false);
        pairingDialog.show();
    }

    private void dismissPairingDialog() {
        pairingAttempt = null;
        if (pairingDialog != null) {
            if (pairingDialog.isShowing()) {
                pairingDialog.dismiss();
            }
            pairingDialog = null;
        }
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.wol_pc_online), HermitNotice.SHORT);
            return;
        }

        if (computer.macAddress == null) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.wol_no_mac), HermitNotice.SHORT);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        HermitNotice.show(PcView.this, toastMessage, HermitNotice.LONG);
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.error_pc_offline), HermitNotice.SHORT);
            return;
        }
        if (managerBinder == null) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
            return;
        }

        HermitNotice.show(PcView.this, getResources().getString(R.string.unpairing), HermitNotice.SHORT);
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, NvHTTP.pairingUniqueId(computer, managerBinder.getUniqueId()),
                            computer.serverCert, PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = ServerHelper.describeHostError(PcView.this, e);
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        HermitNotice.show(PcView.this, toastMessage, HermitNotice.LONG);
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.error_pc_offline), HermitNotice.SHORT);
            return;
        }
        if (managerBinder == null) {
            HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    HermitLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        // Hermit: no name, so the messages say "the running app"
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details),
                        UiHelper.describeComputer(PcView.this, computer.details), false);
                return true;

            case RESTART_PC_ID:
            case SHUTDOWN_PC_ID:
                if (managerBinder == null) {
                    HermitNotice.show(PcView.this, getResources().getString(R.string.error_manager_not_running), HermitNotice.LONG);
                    return true;
                }
                RemoteShutdown.start(PcView.this, computer.details, managerBinder.getUniqueId(),
                        item.getItemId() == RESTART_PC_ID);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}

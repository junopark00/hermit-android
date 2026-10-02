package com.junopark.hermit.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.junopark.hermit.AppView;
import com.junopark.hermit.Game;
import com.junopark.hermit.R;
import com.junopark.hermit.ShortcutTrampoline;
import com.junopark.hermit.binding.PlatformBinding;
import com.junopark.hermit.computers.ComputerManagerService;
import com.junopark.hermit.nvstream.http.ComputerDetails;
import com.junopark.hermit.nvstream.http.HostHttpResponseException;
import com.junopark.hermit.nvstream.http.NvApp;
import com.junopark.hermit.nvstream.http.NvHTTP;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;
import com.junopark.hermit.hermit.HermitNotice;

public class ServerHelper {
    /**
     * Hermit: a host request failure in the user's language instead of the exception's English
     * text (HostHttpResponseException, XML and network errors).
     */
    public static String describeHostError(Context context, Exception e) {
        if (e instanceof HostHttpResponseException) {
            HostHttpResponseException h = (HostHttpResponseException) e;
            if (h.getErrorCode() == 418) {
                // NvHTTP's code for a host without an audio capture device
                return context.getString(R.string.hermit_host_error_audio);
            }
            return context.getString(R.string.hermit_host_error, h.getErrorMessage(), h.getErrorCode());
        }
        else if (e instanceof UnknownHostException) {
            return context.getString(R.string.error_unknown_host);
        }
        else if (e instanceof FileNotFoundException) {
            return context.getString(R.string.error_404);
        }
        else if (e instanceof XmlPullParserException) {
            return context.getString(R.string.hermit_host_bad_response);
        }
        return context.getString(R.string.hermit_host_unreachable);
    }

    /** Hermit: a quit message naming the app, or the running app when the name is unknown. */
    public static String quitMessage(Context context, int namedRes, int unnamedRes, String appName) {
        return appName == null || appName.isEmpty() ? context.getString(unnamedRes) : context.getString(namedRes, appName);
    }

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder) {
        Intent intent = new Intent(parent, Game.class);
        intent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        intent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        intent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        intent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        intent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        intent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        intent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        intent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        intent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        try {
            if (computer.serverCert != null) {
                intent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }
        return intent;
    }

    public static void doStart(Activity parent, NvApp app, ComputerDetails computer,
                               ComputerManagerService.ComputerManagerBinder managerBinder) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            HermitNotice.show(parent, parent.getResources().getString(R.string.pair_pc_offline), HermitNotice.SHORT);
            return;
        }
        parent.startActivity(createStartIntent(parent, app, computer, managerBinder));
    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete) {
        HermitNotice.show(parent, quitMessage(parent, R.string.hermit_quit_in_progress, R.string.hermit_quit_in_progress_any, app.getAppName()), HermitNotice.SHORT);
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                            managerBinder.getUniqueId(), computer.serverCert, PlatformBinding.getCryptoProvider(parent));
                    if (httpConn.quitApp()) {
                        message = quitMessage(parent, R.string.hermit_quit_done, R.string.hermit_quit_done_any, app.getAppName());
                    } else {
                        message = quitMessage(parent, R.string.hermit_quit_failed, R.string.hermit_quit_failed_any, app.getAppName());
                    }
                } catch (HostHttpResponseException e) {
                    if (e.getErrorCode() == 599) {
                        message = parent.getResources().getString(R.string.hermit_quit_not_owner, e.getErrorCode());
                    }
                    else {
                        message = describeHostError(parent, e);
                    }
                } catch (UnknownHostException e) {
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    message = describeHostError(parent, e);
                    e.printStackTrace();
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        HermitNotice.show(parent, toastMessage, HermitNotice.LONG);
                    }
                });
            }
        }).start();
    }
}

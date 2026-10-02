package com.junopark.hermit.preferences;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;

import com.junopark.hermit.binding.PlatformBinding;
import com.junopark.hermit.computers.ComputerManagerService;
import com.junopark.hermit.R;
import com.junopark.hermit.nvstream.http.ComputerDetails;
import com.junopark.hermit.nvstream.http.NvHTTP;
import com.junopark.hermit.utils.Dialog;
import com.junopark.hermit.utils.SpinnerDialog;
import com.junopark.hermit.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import com.junopark.hermit.hermit.HermitNotice;

public class AddComputerManually extends Activity {
    private TextView hostText;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final LinkedBlockingQueue<String> computersToAdd = new LinkedBlockingQueue<>();
    private Thread addThread;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, final IBinder binder) {
            managerBinder = ((ComputerManagerService.ComputerManagerBinder)binder);
            startAddThread();
        }

        public void onServiceDisconnected(ComponentName className) {
            joinAddThread();
            managerBinder = null;
        }
    };

    private boolean isWrongSubnetSiteLocalAddress(String address) {
        try {
            InetAddress targetAddress = InetAddress.getByName(address);
            if (!(targetAddress instanceof Inet4Address) || !targetAddress.isSiteLocalAddress()) {
                return false;
            }

            // We have a site-local address. Look for a matching local interface.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address) || !addr.getAddress().isSiteLocalAddress()) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue;
                    }

                    byte[] targetAddrBytes = targetAddress.getAddress();
                    byte[] ifaceAddrBytes = addr.getAddress().getAddress();

                    // Compare prefix to ensure it's the same
                    boolean addressMatches = true;
                    for (int i = 0; i < addr.getNetworkPrefixLength(); i++) {
                        if ((ifaceAddrBytes[i / 8] & (1 << (i % 8))) != (targetAddrBytes[i / 8] & (1 << (i % 8)))) {
                            addressMatches = false;
                            break;
                        }
                    }

                    if (addressMatches) {
                        return false;
                    }
                }
            }

            // Couldn't find a matching interface
            return true;
        } catch (Exception e) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace();
            return false;
        }
    }

    private URI parseRawUserInputToUri(String rawUserInput) {
        try {
            // Try adding a scheme and parsing the remaining input.
            // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
            URI uri = new URI("hermit://" + rawUserInput);
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        try {
            // Attempt to escape the input as an IPv6 literal.
            // This handles input like ::1.
            URI uri = new URI("hermit://[" + rawUserInput + "]");
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        return null;
    }

    private void doAddPc(String rawUserInput) throws InterruptedException {
        boolean wrongSiteLocal = false;
        boolean invalidInput = false;
        boolean success;

        SpinnerDialog dialog = SpinnerDialog.displayDialog(this, getResources().getString(R.string.title_add_pc),
            getResources().getString(R.string.msg_add_pc), false);

        try {
            ComputerDetails details = new ComputerDetails();

            // Check if we parsed a host address successfully
            URI uri = parseRawUserInputToUri(rawUserInput);
            if (uri != null && uri.getHost() != null && !uri.getHost().isEmpty()) {
                String host = uri.getHost();
                int port = uri.getPort();

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT;
                }

                details.manualAddress = new ComputerDetails.AddressTuple(host, port);
                success = managerBinder.addComputerBlocking(details);
                if (!success){
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host);
                }
            } else {
                // Invalid user input
                success = false;
                invalidInput = true;
            }
        } catch (InterruptedException e) {
            // Propagate the InterruptedException to the caller for proper handling
            dialog.dismiss();
            throw e;
        } catch (IllegalArgumentException e) {
            // This can be thrown from OkHttp if the host fails to canonicalize to a valid name.
            // https://github.com/square/okhttp/blob/okhttp_27/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java#L705
            e.printStackTrace();
            success = false;
            invalidInput = true;
        }

        dialog.dismiss();

        if (invalidInput) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_unknown_host), false);
        }
        else if (wrongSiteLocal) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_wrong_sitelocal), false);
        }
        else if (!success) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_fail), false);
        }
        else {
            AddComputerManually.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                HermitNotice.show(AddComputerManually.this, getResources().getString(R.string.addpc_success), HermitNotice.LONG);

                if (!isFinishing()) {
                    // Close the activity
                    AddComputerManually.this.finish();
                }
                }
            });
        }

    }

    private void startAddThread() {
        addThread = new Thread() {
            @Override
            public void run() {
                while (!isInterrupted()) {
                    try {
                        String computer = computersToAdd.take();
                        doAddPc(computer);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        addThread.setName("UI - AddComputerManually");
        addThread.start();
    }

    private void joinAddThread() {
        if (addThread != null) {
            addThread.interrupt();

            try {
                addThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }

            addThread = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
        SpinnerDialog.closeDialogs(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            joinAddThread();
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_add_computer_manually);

        UiHelper.notifyNewRootView(this);

        this.hostText = findViewById(R.id.hostTextView);
        hostText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hostText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                        (keyEvent != null &&
                                keyEvent.getAction() == KeyEvent.ACTION_DOWN &&
                                keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    return handleDoneEvent();
                }
                else if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                    // This is how the Fire TV dismisses the keyboard
                    InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(hostText.getWindowToken(), 0);
                    return false;
                }

                return false;
            }
        });

        findViewById(R.id.addPcButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleDoneEvent();
            }
        });

        // Bind to the ComputerManager service
        bindService(new Intent(AddComputerManually.this,
                    ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    // Returns true if the event should be eaten
    private boolean handleDoneEvent() {
        String hostAddress = hostText.getText().toString().trim();

        if (hostAddress.length() == 0) {
            HermitNotice.show(AddComputerManually.this, getResources().getString(R.string.addpc_enter_ip), HermitNotice.LONG);
            return true;
        }

        computersToAdd.add(hostAddress);
        return false;
    }
}

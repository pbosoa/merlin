package com.novoda.merlin.service;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.VisibleForTesting;

import com.novoda.merlin.MerlinsBeard;
import com.novoda.merlin.NetworkStatus;
import com.novoda.merlin.RxCallbacksManager;
import com.novoda.merlin.receiver.ConnectivityChangeEvent;
import com.novoda.merlin.receiver.ConnectivityReceiver;
import com.novoda.merlin.registerable.bind.BindListener;
import com.novoda.merlin.registerable.connection.ConnectListener;
import com.novoda.merlin.registerable.disconnection.DisconnectListener;

public class MerlinService extends Service implements HostPinger.PingerCallback {

    public static boolean USE_COMPONENT_ENABLED_SETTING = true;

    private final IBinder binder;
    private NetworkStatusRetriever networkStatusRetriever;
    private HostPinger hostPinger;
    private ConnectivityReceiver connectivityReceiver;

    private ConnectListener connectListener;
    private DisconnectListener disconnectListener;

    private RxCallbacksManager rxCallbacksManager;

    private NetworkStatus networkStatus;

    public MerlinService() {
        binder = new LocalBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        hostPinger = buildDefaultHostPinger();
        networkStatusRetriever = buildNetworkStatusRetriever();
    }

    @VisibleForTesting
    protected NetworkStatusRetriever buildNetworkStatusRetriever() {
        return new NetworkStatusRetriever(MerlinsBeard.from(this.getApplicationContext()));
    }

    @VisibleForTesting
    protected HostPinger buildDefaultHostPinger() {
        return HostPinger.withDefaultEndpointValidation(this);
    }

    public class LocalBinder extends Binder {
        public MerlinService getService() {
            return MerlinService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        enableConnectivityReceiver();
        return binder;
    }

    private void enableConnectivityReceiver() {
        if (isSdkVersionLessThan(Build.VERSION_CODES.N)) {
            setReceiverState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        } else {
            getApplicationContext().registerReceiver(getConnectivityReceiver(), getConnectivityActionIntentFilter());
        }
    }

    @VisibleForTesting
    protected boolean isSdkVersionLessThan(int version) {
        return Build.VERSION.SDK_INT < version;
    }

    @VisibleForTesting
    protected IntentFilter getConnectivityActionIntentFilter() {
        return new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disableConnectivityReceiver();
        return super.onUnbind(intent);
    }

    private void disableConnectivityReceiver() {
        if (isSdkVersionLessThan(Build.VERSION_CODES.N)) {
            setReceiverState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        } else {
            getApplicationContext().unregisterReceiver(getConnectivityReceiver());
        }
    }

    private void setReceiverState(int receiverState) {
        if (USE_COMPONENT_ENABLED_SETTING) {
            getPackageManager().setComponentEnabledSetting(connectivityReceiverComponent(), receiverState, PackageManager.DONT_KILL_APP);
        }
    }

    @VisibleForTesting
    protected ComponentName connectivityReceiverComponent() {
        return new ComponentName(this, ConnectivityReceiver.class);
    }

    @VisibleForTesting
    protected ConnectivityReceiver getConnectivityReceiver() {
        if (null == connectivityReceiver) {
            connectivityReceiver = new ConnectivityReceiver();
        }
        return connectivityReceiver;
    }

    public void setHostname(String hostname, ResponseCodeValidator validator) {
        hostPinger = buildHostPinger(hostname, validator);
    }

    @VisibleForTesting
    protected HostPinger buildHostPinger(String hostName, ResponseCodeValidator validator) {
        return HostPinger.withCustomEndpointAndValidation(this, hostName, validator);
    }

    public void setBindStatusListener(BindListener bindListener) {
        callbackCurrentStatus(bindListener);
    }

    public void setRxCallbacksManager(RxCallbacksManager rxCallbacksManager) {
        this.rxCallbacksManager = rxCallbacksManager;
    }

    private void callbackCurrentStatus(BindListener bindListener) {
        if (bindListener != null) {
            if (networkStatus == null) {
                bindListener.onMerlinBind(networkStatusRetriever.get());
                return;
            }
            bindListener.onMerlinBind(networkStatus);
        }
    }

    public void setConnectListener(ConnectListener connectListener) {
        this.connectListener = connectListener;
    }

    public void setDisconnectListener(DisconnectListener disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    public void onConnectivityChanged(ConnectivityChangeEvent connectivityChangeEvent) {
        if (!connectivityChangeEvent.asNetworkStatus().equals(networkStatus)) {
            getCurrentNetworkStatus();
        }
        networkStatus = connectivityChangeEvent.asNetworkStatus();
    }

    private void getCurrentNetworkStatus() {
        networkStatusRetriever.fetchWithPing(hostPinger);
    }

    @Override
    public void onSuccess() {
        networkStatus = NetworkStatus.newAvailableInstance();
        if (connectListener != null) {
            connectListener.onConnect();
        }
        if (rxCallbacksManager != null) {
            rxCallbacksManager.onConnect();
        }
    }

    @Override
    public void onFailure() {
        networkStatus = NetworkStatus.newUnavailableInstance();
        if (disconnectListener != null) {
            disconnectListener.onDisconnect();
        }
        if (rxCallbacksManager != null) {
            rxCallbacksManager.onDisconnect();
        }
    }

}

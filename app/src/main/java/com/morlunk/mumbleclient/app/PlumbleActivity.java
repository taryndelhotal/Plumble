/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.morlunk.mumbleclient.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.ActionBarDrawerToggle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.morlunk.jumble.Constants;
import com.morlunk.jumble.IJumbleService;
import com.morlunk.jumble.JumbleService;
import com.morlunk.jumble.model.Server;
import com.morlunk.jumble.util.JumbleException;
import com.morlunk.jumble.util.JumbleObserver;
import com.morlunk.jumble.util.MumbleURLParser;
import com.morlunk.jumble.util.ParcelableByteArray;
import com.morlunk.mumbleclient.R;
import com.morlunk.mumbleclient.Settings;
import com.morlunk.mumbleclient.channel.AccessTokenFragment;
import com.morlunk.mumbleclient.channel.ChannelFragment;
import com.morlunk.mumbleclient.channel.ServerInfoFragment;
import com.morlunk.mumbleclient.db.DatabaseProvider;
import com.morlunk.mumbleclient.db.PlumbleDatabase;
import com.morlunk.mumbleclient.db.PlumbleSQLiteDatabase;
import com.morlunk.mumbleclient.db.PublicServer;
import com.morlunk.mumbleclient.preference.PlumbleCertificateGenerateTask;
import com.morlunk.mumbleclient.preference.Preferences;
import com.morlunk.mumbleclient.servers.FavouriteServerListFragment;
import com.morlunk.mumbleclient.servers.PublicServerListFragment;
import com.morlunk.mumbleclient.servers.ServerEditFragment;
import com.morlunk.mumbleclient.service.PlumbleService;
import com.morlunk.mumbleclient.util.JumbleServiceFragment;
import com.morlunk.mumbleclient.util.JumbleServiceProvider;
import com.morlunk.mumbleclient.util.PlumbleTrustStore;

import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import info.guardianproject.onionkit.ui.OrbotHelper;

public class PlumbleActivity extends ActionBarActivity implements ListView.OnItemClickListener,
        FavouriteServerListFragment.ServerConnectHandler, JumbleServiceProvider, DatabaseProvider,
        SharedPreferences.OnSharedPreferenceChangeListener, DrawerAdapter.DrawerDataProvider,
        ServerEditFragment.ServerEditListener {
    /**
     * If specified, the provided integer drawer fragment ID is shown when the activity is created.
     */
    public static final String EXTRA_DRAWER_FRAGMENT = "drawer_fragment";

    private PlumbleService.PlumbleBinder mService;
    private PlumbleDatabase mDatabase;
    private Settings mSettings;

    private ActionBarDrawerToggle mDrawerToggle;
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerList;
    private DrawerAdapter mDrawerAdapter;

    private ProgressDialog mConnectingDialog;
    private AlertDialog mErrorDialog;
    private AlertDialog.Builder mDisconnectPromptBuilder;

    // Represents name of the server.
    public static final String PROTOCOL_SCHEME_RFCOMM = "btspp";

    private Handler m_handler = new Handler();

    private static final int REQUEST_ENABLE_BT = 1;

    // Get Default Adapter.
    private BluetoothAdapter m_bluetooth = BluetoothAdapter.getDefaultAdapter();
    // Server socket.
    private BluetoothServerSocket m_serverSocket;

    // To insure user still sees server info regardless of client request.
    public static Boolean clientRequest = false;

    // To insure only slave devices get disconnected
    public static Boolean slaveServerREQ = false;

    public static String btClientRequest;

    public static Boolean masterRequest = false;
    public static Boolean serverRequest = false;

    // Server info variables.
    public static String name;
    public static String host;
    public static int port;
    public static String username;
    public static String password = "";

    // Initialize socket
    BluetoothSocket socket;

    //Bluetooth Audio FeedBack
    private static final String TAG = "BTAudioFB";
    private MediaPlayer mPlayer = null;
    private AudioManager amanager = null;

    Server userServer;
    Server slaveServer;

    public static Boolean nullThread = false;


    /**
     * List of fragments to be notified about service state changes.
     */
    private List<JumbleServiceFragment> mServiceFragments = new ArrayList<JumbleServiceFragment>();

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = (PlumbleService.PlumbleBinder) service;
            try {
                mService.registerObserver(mObserver);
                mService.clearChatNotifications(); // Clear chat notifications on resume.
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            mDrawerAdapter.notifyDataSetChanged();

            for (JumbleServiceFragment fragment : mServiceFragments)
                fragment.setServiceBound(true);

            // Re-show server list if we're showing a fragment that depends on the service.
            try {
                if (getSupportFragmentManager().findFragmentById(R.id.content_frame) instanceof JumbleServiceFragment &&
                        mService.getConnectionState() != JumbleService.STATE_CONNECTED) {
                    loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                }
                updateConnectionState(getService());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private JumbleObserver mObserver = new JumbleObserver() {
        @Override
        public void onConnected() throws RemoteException {
            loadDrawerFragment(DrawerAdapter.ITEM_SERVER);
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() throws RemoteException {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(JumbleException e) throws RemoteException {
            // Re-show server list if we're showing a fragment that depends on the service.
            if (getSupportFragmentManager().findFragmentById(R.id.content_frame) instanceof JumbleServiceFragment) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onTLSHandshakeFailed(ParcelableByteArray cert) throws RemoteException {
            byte[] certBytes = cert.getBytes();
            final Server lastServer = getService().getConnectedServer();

            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                final X509Certificate x509 = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    byte[] certDigest = digest.digest(x509.getEncoded());
                    String hexDigest = new String(Hex.encode(certDigest));
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
                try {
                    String alias = lastServer.getHost();
                    KeyStore trustStore = PlumbleTrustStore.getTrustStore(PlumbleActivity.this);
                    trustStore.setCertificateEntry(alias, x509);
                    PlumbleTrustStore.saveTrustStore(PlumbleActivity.this, trustStore);
                    Toast.makeText(PlumbleActivity.this, R.string.trust_added, Toast.LENGTH_LONG).show();
                    connectToServer(lastServer);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(PlumbleActivity.this, R.string.trust_add_failed, Toast.LENGTH_LONG).show();
                }
            } catch (CertificateException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onPermissionDenied(String reason) throws RemoteException {
            AlertDialog.Builder adb = new AlertDialog.Builder(PlumbleActivity.this);
            adb.setTitle(R.string.perm_denied);
            adb.setMessage(reason);
            adb.show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mSettings = Settings.getInstance(this);
        setTheme(mSettings.getTheme());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setStayAwake(mSettings.shouldStayAwake());

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        mDatabase = new PlumbleSQLiteDatabase(this); // TODO add support for cloud storage
        mDatabase.open();
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerList = (ListView) findViewById(R.id.left_drawer);
        mDrawerList.setOnItemClickListener(this);
        mDrawerAdapter = new DrawerAdapter(this, this);
        mDrawerList.setAdapter(mDrawerAdapter);
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerClosed(View drawerView) {
                supportInvalidateOptionsMenu();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                super.onDrawerStateChanged(newState);

                try {
                    // Prevent push to talk from getting stuck on when the drawer is opened.
                    if (getService() != null
                            && getService().getConnectionState() == JumbleService.STATE_CONNECTED
                            && getService().isTalking() && !mSettings.isPushToTalkToggle()) {
                        getService().setTalkingState(false);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                supportInvalidateOptionsMenu();
            }
        };

        mDrawerLayout.setDrawerListener(mDrawerToggle);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        // Tint logo to theme
        int iconColor = getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimaryInverse}).getColor(0, -1);
        Drawable logo = getResources().getDrawable(R.drawable.ic_home);
        logo.setColorFilter(iconColor, PorterDuff.Mode.MULTIPLY);
        getSupportActionBar().setLogo(logo);

        AlertDialog.Builder dadb = new AlertDialog.Builder(this);
        dadb.setMessage(R.string.disconnectSure);
        dadb.setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                try {
                    if (mService != null
                            && mService.getConnectionState() == JumbleService.STATE_CONNECTED)
                        mService.disconnect();
                    loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
        dadb.setNegativeButton(android.R.string.cancel, null);
        mDisconnectPromptBuilder = dadb;

        if (savedInstanceState == null) {
            if (getIntent() != null && getIntent().hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(getIntent().getIntExtra(EXTRA_DRAWER_FRAGMENT,
                        DrawerAdapter.ITEM_FAVOURITES));
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
        }

        // If we're given a Mumble URL to show, open up a server edit fragment.
        if (getIntent() != null &&
                Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String url = getIntent().getDataString();
            try {
                Server server = MumbleURLParser.parseURL(url);

                // Open a dialog prompting the user to add the Mumble server.
                Bundle args = new Bundle();
                args.putBoolean("save", false);
                args.putParcelable("server", server);
                ServerEditFragment fragment = (ServerEditFragment) ServerEditFragment.instantiate(this, ServerEditFragment.class.getName(), args);
                fragment.show(getSupportFragmentManager(), "url_edit");
            } catch (MalformedURLException e) {
                Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }

        // Enable bluetooth if not on
        if (!m_bluetooth.isEnabled()) {
            Intent turnOnIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(turnOnIntent, REQUEST_ENABLE_BT);
        }
        // Create BT Service
        try {
            m_serverSocket = m_bluetooth.listenUsingRfcommWithServiceRecord(PROTOCOL_SCHEME_RFCOMM, UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        //SetServerInfo();
        if (mSettings.isFirstRun()) {
            showSetupWizard();
            CreateUserServer();
        }

        //This can be worked on still --- not sure why we get NULL yet
        // Start server socket service
        lastThread = this.m_BTserverThread;
        startService(new Intent(this, SocketService.class));

        // Start thread even if service fails to start
        if (nullThread == true){
            m_BTserverThread.start();
        }

    }

    public void SetServerInfo() {
        // Get server information.
        try {
            name = (ServerEditFragment.mNameEdit).getText().toString().trim();
        } catch (final NullPointerException ex) {
            name = "LincBand";
        }
        try {
            host = (ServerEditFragment.mHostEdit).getText().toString().trim();
        } catch (final NullPointerException ex) {
            host = Constants.DEFAULT_SERVER;
        }
        try {
            port = Integer.parseInt((ServerEditFragment.mPortEdit).getText().toString());
        } catch (final NullPointerException ex) {
            port = Constants.DEFAULT_PORT;
        }
        try {
            username = (ServerEditFragment.mUsernameEdit).getText().toString().trim();
        } catch (final NullPointerException ex) {
            username = "Username";
        }
        try {
            password = ServerEditFragment.mPasswordEdit.getText().toString();
        } catch (final NullPointerException ex) {
            ex.getStackTrace();
        }
    }


    public void CreateUserServer() {
        // Get server information.
        try {
            name = (ServerEditFragment.mNameEdit).getText().toString().trim();
        } catch (final NullPointerException ex) {
            name = "LincBand";
        }
        try {
            host = (ServerEditFragment.mHostEdit).getText().toString().trim();
        } catch (final NullPointerException ex) {
            host = Constants.DEFAULT_SERVER;
        }
        try {
            port = Integer.parseInt((ServerEditFragment.mPortEdit).getText().toString());
        } catch (final NullPointerException ex) {
            port = Constants.DEFAULT_PORT;
        }
        try {
            username = (ServerEditFragment.mUsernameEdit).getText().toString().trim();
        } catch (final NullPointerException ex) {
            username = "Username";
        }
        try {
            password = ServerEditFragment.mPasswordEdit.getText().toString();
        } catch (final NullPointerException ex) {
            ex.getStackTrace();
        }

        userServer = new Server(18, name, host, port, username, password);
        getDatabase().addServer(userServer);
        serverInfoUpdated();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent connectIntent = new Intent(this, PlumbleService.class);
        bindService(connectIntent, mConnection, 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mErrorDialog != null)
            mErrorDialog.dismiss();
        if (mConnectingDialog != null)
            mConnectingDialog.dismiss();

        if (mService != null)
            try {
                for (JumbleServiceFragment fragment : mServiceFragments)
                    fragment.setServiceBound(false);
                mService.unregisterObserver(mObserver);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        unbindService(mConnection);
    }

    @Override
    protected void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        mDatabase.close();
        // stopService(new Intent(this, SocketService.class));
        super.onDestroy();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem disconnectButton = menu.findItem(R.id.action_disconnect);
        try {
            disconnectButton.setVisible(mService != null &&
                    mService.getConnectionState() == JumbleService.STATE_CONNECTED);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        // Color the action bar icons to the primary text color of the theme.
        int foregroundColor = getSupportActionBar().getThemedContext()
                .obtainStyledAttributes(new int[]{android.R.attr.textColor})
                .getColor(0, -1);
        for (int x = 0; x < menu.size(); x++) {
            MenuItem item = menu.getItem(x);
            if (item.getIcon() != null) {
                Drawable icon = item.getIcon().mutate(); // Mutate the icon so that the color filter is exclusive to the action bar
                icon.setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY);
            }
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.plumble, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item))
            return true;

        switch (item.getItemId()) {
            case R.id.action_disconnect:
                try {
                    getService().disconnect();
                    // Remove server from server list if disconnected only if it is the slave server
                    if (slaveServerREQ == true) {
                        FavouriteServerListFragment.deleteDisconnectedServer(getService().getConnectedServer());
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                return true;
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            if (Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod()) &&
                    keyCode == mSettings.getPushToTalkKey() &&
                    mService != null &&
                    mService.getConnectionState() == JumbleService.STATE_CONNECTED) {
                if (!mService.isTalking() && !mSettings.isPushToTalkToggle()) {
                    mService.setTalkingState(true);
                }
                return true;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        try {
            if (Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod()) &&
                    keyCode == mSettings.getPushToTalkKey() &&
                    mService != null &&
                    mService.getConnectionState() == JumbleService.STATE_CONNECTED) {
                if (!mSettings.isPushToTalkToggle() && mService.isTalking()) {
                    mService.setTalkingState(false);
                } else {
                    mService.setTalkingState(!mService.isTalking());
                }
                return true;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        try {
            if (mService != null &&
                    mService.getConnectionState() == JumbleService.STATE_CONNECTED) {
                mDisconnectPromptBuilder.show();
                return;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        super.onBackPressed();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mDrawerLayout.closeDrawers();
        loadDrawerFragment((int) id);
    }

    /**
     * Shows a nice looking setup wizard to guide the user through the app's settings.
     * Will do nothing if it isn't the first launch.
     */
    private void showSetupWizard() {
        // Prompt the user to generate a certificate, FIXME
        if (mSettings.isUsingCertificate()) return;
        AlertDialog.Builder adb = new AlertDialog.Builder(this);
        adb.setTitle(R.string.first_run_generate_certificate_title);
        adb.setMessage(R.string.first_run_generate_certificate);
        adb.setPositiveButton(R.string.generate, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlumbleCertificateGenerateTask generateTask = new PlumbleCertificateGenerateTask(PlumbleActivity.this) {
                    @Override
                    protected void onPostExecute(File result) {
                        super.onPostExecute(result);
                        if (result != null) mSettings.setCertificatePath(result.getAbsolutePath());
                    }
                };
                generateTask.execute();
            }
        });
        adb.show();
        mSettings.setFirstRun(false);

        // TODO: finish wizard
//        Intent intent = new Intent(this, WizardActivity.class);
//        startActivity(intent);
    }

    /**
     * Loads a fragment from the drawer.
     */
    private void loadDrawerFragment(int fragmentId) {
        Class<? extends Fragment> fragmentClass = null;
        Bundle args = new Bundle();
        switch (fragmentId) {
            case DrawerAdapter.ITEM_SERVER:
                fragmentClass = ChannelFragment.class;
                break;
            case DrawerAdapter.ITEM_INFO:
                fragmentClass = ServerInfoFragment.class;
                break;
            case DrawerAdapter.ITEM_ACCESS_TOKENS:
                fragmentClass = AccessTokenFragment.class;
                try {
                    args.putLong("server", mService.getConnectedServer().getId());
                    args.putStringArrayList("access_tokens", (ArrayList<String>) mDatabase.getAccessTokens(mService.getConnectedServer().getId()));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                break;
            case DrawerAdapter.ITEM_PINNED_CHANNELS:
                fragmentClass = ChannelFragment.class;
                args.putBoolean("pinned", true);
                break;
            case DrawerAdapter.ITEM_FAVOURITES:
                fragmentClass = FavouriteServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_PUBLIC:
                fragmentClass = PublicServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_SETTINGS:
                Intent prefIntent = new Intent(this, Preferences.class);
                startActivity(prefIntent);
                return;
            default:
                return;
        }
        Fragment fragment = Fragment.instantiate(this, fragmentClass.getName(), args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, fragment, fragmentClass.getName())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commitAllowingStateLoss(); //commit;
        setTitle(mDrawerAdapter.getItemWithId(fragmentId).title);
    }

    public void connectToServer(final Server server) {
        // Check if we're already connected to a server; if so, inform user.
        try {
            if (mService != null &&
                    mService.getConnectionState() == JumbleService.STATE_CONNECTED) {
                // AlertDialog.Builder adb = new AlertDialog.Builder(this);
                //adb.setMessage(R.string.reconnect_dialog_message);
                //adb.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
                //@Override
                //public void onClick(DialogInterface dialog, int which) {
                try {
                    // Register an observer to reconnect to the new server once disconnected.
                    mService.registerObserver(new JumbleObserver() {
                        @Override
                        public void onDisconnected(JumbleException e) throws RemoteException {
                            connectToServer(server);
                            mService.unregisterObserver(this);
                        }
                    });
                    mService.disconnect();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                //}
                // });
                // adb.setNegativeButton(android.R.string.cancel, null);
                //adb.show();
                return;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        // Prompt to start Orbot if enabled but not running
        if (mSettings.isTorEnabled()) {
            OrbotHelper orbotHelper = new OrbotHelper(this);
            if (!orbotHelper.isOrbotRunning()) {
                orbotHelper.requestOrbotStart(this);
                return;
            }
        }

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
    }

    public void connectToPublicServer(final PublicServer server) {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);

        final Settings settings = Settings.getInstance(this);

        // Allow username entry
        final EditText usernameField = new EditText(this);
        usernameField.setHint(settings.getDefaultUsername());
        alertBuilder.setView(usernameField);

        alertBuilder.setTitle(R.string.connectToServer);

        alertBuilder.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PublicServer newServer = server;
                if (!usernameField.getText().toString().equals(""))
                    newServer.setUsername(usernameField.getText().toString());
                else
                    newServer.setUsername(settings.getDefaultUsername());
                connectToServer(newServer);
            }
        });

        alertBuilder.show();
    }

    private void setStayAwake(boolean stayAwake) {
        if (stayAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * Updates the activity to represent the connection state of the given service.
     * Will show reconnecting dialog if reconnecting, dismiss otherwise, etc.
     * Basically, this service will do catch-up if the activity wasn't bound to receive
     * connection state updates.
     *
     * @param service A bound IJumbleService.
     */
    private void updateConnectionState(IJumbleService service) throws RemoteException {
        if (mConnectingDialog != null)
            mConnectingDialog.dismiss();
        if (mErrorDialog != null)
            mErrorDialog.dismiss();

        switch (mService.getConnectionState()) {
            case JumbleService.STATE_CONNECTING:
                Server server = service.getConnectedServer();
                mConnectingDialog = new ProgressDialog(this);
                mConnectingDialog.setIndeterminate(true);
                mConnectingDialog.setCancelable(true);
                mConnectingDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        try {
                            mService.disconnect();
                            Toast.makeText(PlumbleActivity.this, R.string.cancelled,
                                    Toast.LENGTH_SHORT).show();
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                });
                mConnectingDialog.setMessage(getString(R.string.connecting_to_server, server.getHost(),
                        server.getPort()));
                mConnectingDialog.show();
                break;
            case JumbleService.STATE_CONNECTION_LOST:
                // Only bother the user if the error hasn't already been shown.
                if (!getService().isErrorShown()) {
                    JumbleException error = getService().getConnectionError();
                    AlertDialog.Builder ab = new AlertDialog.Builder(PlumbleActivity.this);
                    ab.setTitle(R.string.connectionRefused);
                    if (mService.isReconnecting()) {
                        ab.setMessage(getString(R.string.attempting_reconnect, error.getMessage()));
                        ab.setPositiveButton(R.string.cancel_reconnect, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (getService() != null) {
                                    try {
                                        getService().cancelReconnect();
                                        getService().markErrorShown();
                                    } catch (RemoteException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        });
                    } else {
                        ab.setMessage(error.getMessage());
                        ab.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (getService() != null)
                                    getService().markErrorShown();
                            }
                        });
                    }
                    ab.setCancelable(false);
                    mErrorDialog = ab.show();
                }
                break;


        }
    }

    @Override
    public void serverInfoUpdated() {
        loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    @Override
    public PlumbleService.PlumbleBinder getService() {
        return mService;
    }

    @Override
    public PlumbleDatabase getDatabase() {
        return mDatabase;
    }

    @Override
    public void addServiceFragment(JumbleServiceFragment fragment) {
        mServiceFragments.add(fragment);
    }

    @Override
    public void removeServiceFragment(JumbleServiceFragment fragment) {
        mServiceFragments.remove(fragment);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Settings.PREF_THEME.equals(key)) {
            // Recreate activity when theme is changed
            if (Build.VERSION.SDK_INT >= 11)
                recreate();
            else {
                Intent intent = new Intent(this, PlumbleActivity.class);
                finish();
                startActivity(intent);
            }
        } else if (Settings.PREF_STAY_AWAKE.equals(key)) {
            setStayAwake(mSettings.shouldStayAwake());
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return mService != null
                    && mService.getConnectionState() == JumbleService.STATE_CONNECTED;
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String getConnectedServerName() {
        try {
            if (mService != null
                    && mService.getConnectionState() == JumbleService.STATE_CONNECTED) {
                Server server = mService.getConnectedServer();
                return server.getName().equals("") ? server.getHost() : server.getName();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void sendAudioFB() {
        amanager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        amanager.setBluetoothScoOn(true);
        amanager.startBluetoothSco();
        amanager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        mPlayer = new MediaPlayer();

        try {
            AssetFileDescriptor afd = getAssets().openFd("SummerBling.mp3");
            MediaPlayer player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);

            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                }
            });

            player.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);
            player.prepareAsync();

        } catch (IOException ioe) {
            System.err.println(ioe.getLocalizedMessage());
        }
    }

    public Thread m_audioFBThread = new Thread() {
        public void run() {
            sendAudioFB();
        } ;
    };

    public static Thread lastThread = null;

    public Thread m_BTserverThread = new Thread() {
        public void run() {
            listenBTclientReq();
        };
    };

    protected void listenBTclientReq() {
        while (true) {
            try {
                Log.v("RUNNING:", "SERVICE******************************************" );
                // Accept Client request
                socket = m_serverSocket.accept();

                // Process the Client Request
                if (socket != null) {
                    final InputStream inputStream = socket.getInputStream();
                    final OutputStream outputStream = socket.getOutputStream();

                    int readBytes = -1;
                    final byte[] bytes = new byte[2048];
                    for (; (readBytes = inputStream.read(bytes)) > -1; ) {
                        final int count = readBytes;
                        m_handler.post(new Runnable() {
                            @Override
                            public void run() {
                                // Client Request Stored.
                                btClientRequest = new String(bytes, 0, count);
                                Log.v("REQUEST:", "msg" + btClientRequest);

                                // Handles master situation.
                                if (btClientRequest.equals("GetServerInfo")) {

                                    // Ensures userServer never gets deleted
                                    slaveServerREQ= false;

                                    // Writes server information to Linc Band client.
                                    String serverInfo = "SlaveBand" + "," + userServer.getHost() + "," + userServer.getPort() + "," + password;
                                    byte[] sendByte = serverInfo.getBytes();
                                    try {
                                        outputStream.write(sendByte);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                    connectToServer(userServer);

                                    sendAudioFB();
                                }

                                // Handles slave situation
                                if (btClientRequest.contains("PutServerInfo")) {
                                    // Allows server to be deleted upon manually disconnecting
                                    slaveServerREQ= true;

                                    // Stores received server information
                                    String[] serverInfo = btClientRequest.split(",");
                                    name = serverInfo[1];
                                    host = serverInfo[2];
                                    port = Integer.parseInt(serverInfo[3]);
                                    try {
                                        username = (ServerEditFragment.mUsernameEdit).getText().toString().trim();
                                    } catch (final NullPointerException ex) {
                                        ex.getStackTrace();
                                    }
                                    /*try {
                                        password = serverInfo[4];
                                    } catch (final NullPointerException ex) {
                                        ex.getStackTrace();
                                    }*/

                                    slaveServer = new Server(-1, name, host, port, username, "");
                                    getDatabase().addServer(slaveServer);
                                    serverInfoUpdated();
                                    connectToServer(slaveServer);

                                    sendAudioFB();
                                }
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static class SocketService extends Service {

        public SocketService() {
            super();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onCreate() {
            // This new service was created
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.i("LocalService", "Received start id " + startId + ": " + intent);
            // We want this service to continue running until it is explicitly
            // stopped, so return sticky.
            if(lastThread != null){
                lastThread.start();
            }else{
              nullThread = true;
            }
            return START_STICKY;
        }

        @Override
        public void onDestroy() {
            stopService(new Intent(this, SocketService.class));
            super.onDestroy();
        }
    }
}


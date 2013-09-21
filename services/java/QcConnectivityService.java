/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
 *
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.RECEIVE_DATA_ACTIVITY_CHANGE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_DUMMY;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothTetheringDataTracker;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.CaptivePortalTracker;
import android.net.ConnectivityManager;
import android.net.DummyDataStateTracker;
import android.net.EthernetDataTracker;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Uri;
import android.net.LinkProperties.CompareResult;
import android.net.MobileDataStateTracker;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.RouteInfo;
import android.net.wifi.WifiStateTracker;
import android.net.wimax.WimaxManagerConstants;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.StateMachine;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.server.AlarmManagerService;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.android.server.power.PowerManagerService;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.Set;


/**
 * @hide
 */
public class QcConnectivityService extends ConnectivityService {


  /**
   * QCCS intents
   */
  /**
   * Broadcast intent to notify connectivity changes in dual
   * network mode
   */
  public static final String CONNECTIVITY_AVAILABLE = "CONNECTIVITY_AVAILABLE";
  /**
   * (ConnectivityManager) Network type that triggered broadcast
   * Retreieve with android.content.Intent.getIntExtra(String, int)
   */
  public static final String EXTRA_NETWORK_TYPE = "netType";

  private static final String TAG = "QcConnectivityService";

  private static final boolean DBG = true;
  private static final boolean VDBG = true;

  private static final boolean LOGD_RULES = false;

  // TODO: create better separation between radio types and network types

  // how long to wait before switching back to a radio's default network
  private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
  // system property that can override the above value
  private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
    "android.telephony.apn-restore";

  // Default value if FAIL_FAST_TIME_MS is not set
  private static final int DEFAULT_FAIL_FAST_TIME_MS = 1 * 60 * 1000;
  // system property that can override DEFAULT_FAIL_FAST_TIME_MS
  private static final String FAIL_FAST_TIME_MS =
    "persist.radio.fail_fast_time_ms";

  // used in recursive route setting to add gateways for the host for which
  // a host route was requested.
  private static final int MAX_HOSTROUTE_CYCLE_COUNT = 10;

  private Tethering mTethering;
  private boolean mTetheringConfigValid = false;

  private KeyStore mKeyStore;

  private boolean mLockdownEnabled;
  private LockdownVpnTracker mLockdownTracker;

  private Nat464Xlat mClat;

  /** Lock around {@link #mUidRules} and {@link #mMeteredIfaces}. */
  private Object mRulesLock = new Object();
  /** Currently active network rules by UID. */
  private SparseIntArray mUidRules = new SparseIntArray();
  /** Set of ifaces that are costly. */
  private HashSet<String> mMeteredIfaces = Sets.newHashSet();

  /**
   * Sometimes we want to refer to the individual network state
   * trackers separately, and sometimes we just want to treat them
   * abstractly.
   */
  private NetworkStateTracker mNetTrackers[];

  /* Handles captive portal check on a network */
  private CaptivePortalTracker mCaptivePortalTracker;

  /**
   * The link properties that define the current links
   */
  private LinkProperties mCurrentLinkProperties[];

  /**
   * A per Net list of the PID's that requested access to the net
   * used both as a refcount and for per-PID DNS selection
   */
  private List<Integer> mNetRequestersPids[];

  // priority order of the nettrackers
  // (excluding dynamically set mNetworkPreference)
  // TODO - move mNetworkTypePreference into this
  private int[] mPriorityList;

  private Context mContext;
  private int mNetworkPreference;
  private int mActiveDefaultNetwork = -1;
  // 0 is full bad, 100 is full good
  private int mDefaultInetCondition = 0;
  private int mDefaultInetConditionPublished = 0;
  private boolean mInetConditionChangeInFlight = false;
  private int mDefaultConnectionSequence = 0;

  private Object mDnsLock = new Object();
  private int mNumDnsEntries;
  private boolean mDnsOverridden = false;

  private boolean mTestMode;
  private static ConnectivityService sServiceInstance;

  private INetworkManagementService mNetd;
  private INetworkPolicyManager mPolicyManager;

  private static final int ENABLED  = 1;
  private static final int DISABLED = 0;

  private static final boolean ADD = true;
  private static final boolean REMOVE = false;

  private static final boolean TO_DEFAULT_TABLE = true;
  private static final boolean TO_SECONDARY_TABLE = false;
  private static final int MAX_NETWORK_STATE_TRACKER_EVENT = 100;
  /**
   * used internally as a delayed event to make us switch back to the
   * default network
   */
  private static final int EVENT_RESTORE_DEFAULT_NETWORK =
    MAX_NETWORK_STATE_TRACKER_EVENT + 1;

  /**
   * used internally to change our mobile data enabled flag
   */
  private static final int EVENT_CHANGE_MOBILE_DATA_ENABLED =
    MAX_NETWORK_STATE_TRACKER_EVENT + 2;

  /**
   * used internally to change our network preference setting
   * arg1 = networkType to prefer
   */
  private static final int EVENT_SET_NETWORK_PREFERENCE =
    MAX_NETWORK_STATE_TRACKER_EVENT + 3;

  /**
   * used internally to synchronize inet condition reports
   * arg1 = networkType
   * arg2 = condition (0 bad, 100 good)
   */
  private static final int EVENT_INET_CONDITION_CHANGE =
    MAX_NETWORK_STATE_TRACKER_EVENT + 4;

  /**
   * used internally to mark the end of inet condition hold periods
   * arg1 = networkType
   */
  private static final int EVENT_INET_CONDITION_HOLD_END =
    MAX_NETWORK_STATE_TRACKER_EVENT + 5;

  /**
   * used internally to set enable/disable cellular data
   * arg1 = ENBALED or DISABLED
   */
  private static final int EVENT_SET_MOBILE_DATA =
    MAX_NETWORK_STATE_TRACKER_EVENT + 7;

  /**
   * used internally to clear a wakelock when transitioning
   * from one net to another
   */
  private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK =
    MAX_NETWORK_STATE_TRACKER_EVENT + 8;

  /**
   * used internally to reload global proxy settings
   */
  private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY =
    MAX_NETWORK_STATE_TRACKER_EVENT + 9;

  /**
   * used internally to set external dependency met/unmet
   * arg1 = ENABLED (met) or DISABLED (unmet)
   * arg2 = NetworkType
   */
  private static final int EVENT_SET_DEPENDENCY_MET =
    MAX_NETWORK_STATE_TRACKER_EVENT + 10;

  /**
   * used internally to restore DNS properties back to the
   * default network
   */
  private static final int EVENT_RESTORE_DNS =
    MAX_NETWORK_STATE_TRACKER_EVENT + 11;

  /**
   * used internally to send a sticky broadcast delayed.
   */
  private static final int EVENT_SEND_STICKY_BROADCAST_INTENT =
    MAX_NETWORK_STATE_TRACKER_EVENT + 12;

  /**
   * Used internally to
   * {@link NetworkStateTracker#setPolicyDataEnable(boolean)}.
   */
  private static final int EVENT_SET_POLICY_DATA_ENABLE = MAX_NETWORK_STATE_TRACKER_EVENT + 13;

  private static final int EVENT_VPN_STATE_CHANGED = MAX_NETWORK_STATE_TRACKER_EVENT + 14;

  /**
   * Used internally to disable fail fast of mobile data
   */
  private static final int EVENT_ENABLE_FAIL_FAST_MOBILE_DATA =
    MAX_NETWORK_STATE_TRACKER_EVENT + 15;

  public static final int EVENT_UPDATE_BLOCKED_UID = 501;
  public static final int EVENT_REPRIORITIZE_DNS = 502;
  public static final int EVENT_CONNECTIVITY_SWITCH = 503;
  public static final int EVENT_AVOID_UNSUITABLE_WIFI = 504;

  public QcConnectivityService(Context context, INetworkManagementService netd,
      INetworkStatsService statsService, INetworkPolicyManager policyManager) {
    // Currently, omitting a NetworkFactory will create one internally
    // TODO: create here when we have cleaner WiMAX support
    super(context, netd, statsService, policyManager, null);
  }
}

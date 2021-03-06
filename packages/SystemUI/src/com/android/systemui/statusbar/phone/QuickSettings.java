/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.ValueAnimator;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LevelListDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplayStatus;
import android.media.MediaRouter;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Profile;
import android.provider.Settings;
import android.security.KeyChain;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.app.MediaRouteDialogPresenter;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsModel.ActivityState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.BluetoothState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.RSSIState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.State;
import com.android.systemui.statusbar.phone.QuickSettingsModel.UserState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.WifiState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

/**
 *
 */
public class QuickSettings {
    static final boolean DEBUG_GONE_TILES = false;
    private static final String TAG = "QuickSettings";
    public static final boolean SHOW_IME_TILE = false;

    public static final boolean LONG_PRESS_TOGGLES = true;

    private Context mContext;
    private PanelBar mBar;
    private QuickSettingsModel mModel;
    private ViewGroup mContainerView;

	private DisplayManager mDisplayManager;
	private DevicePolicyManager mDevicePolicyManager;
	private PhoneStatusBar mStatusBarService;
	private BluetoothState mBluetoothState;
	private BluetoothAdapter mBluetoothAdapter;
	private WifiManager mWifiManager;

    private BluetoothController mBluetoothController;
    private RotationLockController mRotationLockController;
    private LocationController mLocationController;

    private AsyncTask<Void, Void, Pair<String, Drawable>> mUserInfoTask;
    private AsyncTask<Void, Void, Pair<Boolean, Boolean>> mQueryCertTask;

    boolean mTilesSetUp = false;
    boolean mUseDefaultAvatar = false;

    private Handler mHandler;

	ArrayList<String> mUserInformation = new ArrayList<String>();

    // The set of QuickSettingsTiles that have dynamic spans (and need to be updated on
    // configuration change)
    private final ArrayList<QuickSettingsTileView> mDynamicSpannedTiles =
            new ArrayList<QuickSettingsTileView>();

    public QuickSettings(Context context, QuickSettingsContainerView container) {
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mDevicePolicyManager
            = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mContext = context;
        mContainerView = container;
        mModel = new QuickSettingsModel(context);
        mBluetoothState = new QuickSettingsModel.BluetoothState();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        mHandler = new Handler();

        IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_USER_SWITCHED);
		filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
		filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
		mContext.registerReceiver(mReceiver, filter);

        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(ContactsContract.Intents.ACTION_PROFILE_CHANGED);
        profileFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mContext.registerReceiverAsUser(mProfileReceiver, UserHandle.ALL, profileFilter,
                null, null);
        
        IntentFilter hiveFilter = new IntentFilter();
        profileFilter.addAction("hive.action.General");
        mContext.registerReceiver(mHiveReceiver, hiveFilter);
    }

    void setBar(PanelBar bar) {
        mBar = bar;
    }

    public void setService(PhoneStatusBar phoneStatusBar) {
        mStatusBarService = phoneStatusBar;
    }

    public PhoneStatusBar getService() {
        return mStatusBarService;
    }

    void setup(NetworkController networkController, BluetoothController bluetoothController,
            BatteryController batteryController, LocationController locationController,
            RotationLockController rotationLockController) {
        mBluetoothController = bluetoothController;
        mRotationLockController = rotationLockController;
        mLocationController = locationController;

        setupQuickSettings();
        updateResources();

        batteryController.addStateChangedCallback(mModel);
    }

    private void queryForSslCaCerts() {
        mQueryCertTask = new AsyncTask<Void, Void, Pair<Boolean, Boolean>>() {
            @Override
            protected Pair<Boolean, Boolean> doInBackground(Void... params) {
                boolean hasCert = DevicePolicyManager.hasAnyCaCertsInstalled();
                boolean isManaged = mDevicePolicyManager.getDeviceOwner() != null;

                return Pair.create(hasCert, isManaged);
            }
            @Override
            protected void onPostExecute(Pair<Boolean, Boolean> result) {
                super.onPostExecute(result);
                boolean hasCert = result.first;
                boolean isManaged = result.second;
                mModel.setSslCaCertWarningTileInfo(hasCert, isManaged);
            }
        };
        mQueryCertTask.execute();
    }

    private void queryForUserInformation() {
        Context currentUserContext = null;
        UserInfo userInfo = null;
        try {
            userInfo = ActivityManagerNative.getDefault().getCurrentUser();
            currentUserContext = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userInfo.id));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't create user context", e);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get user info", e);
        }
        final int userId = userInfo.id;
        final String userName = userInfo.name;

        final Context context = currentUserContext;
        mUserInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
                final UserManager um = UserManager.get(mContext);

                // Fall back to the UserManager nickname if we can't read the name from the local
                // profile below.
                String name = userName;
                Drawable avatar = null;
                Bitmap rawAvatar = um.getUserIcon(userId);
                if (rawAvatar != null) {
                    avatar = new BitmapDrawable(mContext.getResources(), rawAvatar);
                } else {
                    avatar = mContext.getResources().getDrawable(R.drawable.ic_qs_default_user);
                    mUseDefaultAvatar = true;
                }

                // If it's a single-user device, get the profile name, since the nickname is not
                // usually valid
                if (um.getUsers().size() <= 1) {
                    // Try and read the display name from the local profile
                    final Cursor cursor = context.getContentResolver().query(
                            Profile.CONTENT_URI, new String[] {Phone._ID, Phone.DISPLAY_NAME},
                            null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(cursor.getColumnIndex(Phone.DISPLAY_NAME));
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
                return new Pair<String, Drawable>(name, avatar);
            }

            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                super.onPostExecute(result);
                mModel.setUserTileInfo(result.first, result.second);
                mUserInfoTask = null;
            }
        };
        mUserInfoTask.execute();
    }

    private void setupQuickSettings() {
        // Setup the tiles that we are going to be showing (including the temporary ones)
        LayoutInflater inflater = LayoutInflater.from(mContext);

        addUserTiles(mContainerView, inflater);
        addSystemTiles(mContainerView, inflater);
        addTemporaryTiles(mContainerView, inflater);

        queryForUserInformation();
        queryForSslCaCerts();
        mTilesSetUp = true;
    }

    private void startSettingsActivity(String action) {
        Intent intent = new Intent(action);
        startSettingsActivity(intent);
    }

    private void startSettingsActivity(Intent intent) {
        startSettingsActivity(intent, true);
    }

    private void collapsePanels() {
        getService().animateCollapsePanels();
    }

    private void startSettingsActivity(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !getService().isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        collapsePanels();
    }

    private void addUserTiles(ViewGroup parent, LayoutInflater inflater) {
        QuickSettingsTileView userTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        userTile.setContent(R.layout.quick_settings_tile_user, inflater);
        userTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                final UserManager um = UserManager.get(mContext);
                collapsePanels();

                Intent mUserDialogIntent = new Intent(Intent.ACTION_ALL_APPS);
                mUserDialogIntent.setClassName("hive.framework",
                                    "hive.framework.user.UserDetailsDialog");
                mUserDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(mUserDialogIntent);
            }
        });
        mModel.addUserTile(userTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                readInformation();
				

				String mUserAvatarPath = Environment
						.getExternalStorageDirectory()
						+ "/HIVE/User/avatar.png";
				File mUserAvatarFile = new File(mUserAvatarPath);
				File mUserInfoFile = new File(Environment
						.getExternalStorageDirectory()
						+ "/HIVE/User/information");

				BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
				mBitmapOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
				Bitmap mUserAvatar = BitmapFactory.decodeFile(mUserAvatarPath,
						mBitmapOptions);

				String mDefaultUserName = "NOT LOGGED IN";
				UserState us = (UserState) state;
				ImageView iv = (ImageView) view
						.findViewById(R.id.user_imageview);
				TextView tv = (TextView) view.findViewById(R.id.user_textview);

				if (mUserAvatarFile.exists() && mUserInfoFile.exists()) {
					iv.setImageBitmap(mUserAvatar);
					tv.setText(mUserInformation.get(0).toUpperCase());
					mStatusBarService.setHIVEUserName(mUserInformation.get(0).toUpperCase());
				} else {
					iv.setImageResource(R.drawable.ic_qs_default_user);
					tv.setText(mDefaultUserName);
					mStatusBarService.setHIVEUserName("");

				}
                view.setContentDescription(mContext.getString(
                        R.string.accessibility_quick_settings_user, state.label));
            }
        });
        parent.addView(userTile);
        mDynamicSpannedTiles.add(userTile);

        // Brightness
        final QuickSettingsBasicTile brightnessTile
                = new QuickSettingsBasicTile(mContext);
        brightnessTile.setImageResource(R.drawable.ic_qs_brightness_auto_off);
        brightnessTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                showBrightnessDialog();
            }
        });
        mModel.addBrightnessTile(brightnessTile,
                new QuickSettingsModel.BasicRefreshCallback(brightnessTile));
        parent.addView(brightnessTile);
        mDynamicSpannedTiles.add(brightnessTile);

        // Settings tile
        final QuickSettingsBasicTile settingsTile = new QuickSettingsBasicTile(mContext);
        settingsTile.setImageResource(R.drawable.ic_qs_settings);
        settingsTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent mHIVESettingsIntent = new Intent(Intent.ACTION_ALL_APPS);
				mHIVESettingsIntent.setClassName("hive.framework",
						"hive.framework.settings.SettingsActivity");
				mHIVESettingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				mContext.startActivity(mHIVESettingsIntent);
				collapsePanels();
            }
        });
        mModel.addSettingsTile(settingsTile,
                new QuickSettingsModel.BasicRefreshCallback(settingsTile));
        parent.addView(settingsTile);
        mDynamicSpannedTiles.add(settingsTile);
    }

	public void readInformation() {
		File mUserInfoFile = new File(Environment
				.getExternalStorageDirectory()
				+ "/HIVE/User/information");
		
		mUserInformation.clear();
		
		try {
			BufferedReader mBufferReader = new BufferedReader(
					new FileReader(mUserInfoFile));
			String line;

			while ((line = mBufferReader.readLine()) != null) {
				mUserInformation.add(line);
			}
		} catch (IOException e) {
		}
	}

    private void addSystemTiles(ViewGroup parent, LayoutInflater inflater) {
        // Battery
        final QuickSettingsTileView batteryTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        batteryTile.setContent(R.layout.quick_settings_tile_battery, inflater);
        batteryTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(Intent.ACTION_POWER_USAGE_SUMMARY);
            }
        });
        mModel.addBatteryTile(batteryTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State state) {
                QuickSettingsModel.BatteryState batteryState =
                        (QuickSettingsModel.BatteryState) state;
                String t;
                if (batteryState.batteryLevel == 100) {
                    t = mContext.getString(R.string.quick_settings_battery_charged_label);
                } else {
                    t = batteryState.pluggedIn
                        ? mContext.getString(R.string.quick_settings_battery_charging_label,
                                batteryState.batteryLevel)
                        : mContext.getString(R.string.status_bar_settings_battery_meter_format,
                                batteryState.batteryLevel);
                }
                ((TextView)batteryTile.findViewById(R.id.text)).setText(t);
                batteryTile.setContentDescription(
                        mContext.getString(R.string.accessibility_quick_settings_battery, t));
            }
        });
        parent.addView(batteryTile);
    
    }
    
    private void addTemporaryTiles(final ViewGroup parent, final LayoutInflater inflater) {

        // SSL CA Cert Warning.
        final QuickSettingsBasicTile sslCaCertWarningTile =
                new QuickSettingsBasicTile(mContext, null, R.layout.quick_settings_tile_monitoring);
        sslCaCertWarningTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                startSettingsActivity(Settings.ACTION_MONITORING_CERT_INFO);
            }
        });

        sslCaCertWarningTile.setImageResource(
                com.android.internal.R.drawable.indicator_input_error);
        sslCaCertWarningTile.setTextResource(R.string.ssl_ca_cert_warning);

        mModel.addSslCaCertWarningTile(sslCaCertWarningTile,
                new QuickSettingsModel.BasicRefreshCallback(sslCaCertWarningTile)
                        .setShowWhenEnabled(true));
        parent.addView(sslCaCertWarningTile);
    }

    void updateResources() {
        Resources r = mContext.getResources();

        // Update the model
        mModel.updateResources();

        // Update the User, Time, and Settings tiles spans, and reset everything else
        int span = r.getInteger(R.integer.quick_settings_user_time_settings_tile_span);
        for (QuickSettingsTileView v : mDynamicSpannedTiles) {
            v.setColumnSpan(span);
        }
        ((QuickSettingsContainerView)mContainerView).updateResources();
        mContainerView.requestLayout();
    }


    private void showBrightnessDialog() {
        Intent intent = new Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG);
        mContext.sendBroadcast(intent);
    }

    private void showBugreportDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setPositiveButton(com.android.internal.R.string.report, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Add a little delay before executing, to give the
                    // dialog a chance to go away before it takes a
                    // screenshot.
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                ActivityManagerNative.getDefault()
                                        .requestBugReport();
                            } catch (RemoteException e) {
                            }
                        }
                    }, 500);
                }
            }
        });
        builder.setMessage(com.android.internal.R.string.bugreport_message);
        builder.setTitle(com.android.internal.R.string.bugreport_title);
        builder.setCancelable(true);
        final Dialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
        } catch (RemoteException e) {
        }
        dialog.show();
    }

    void reloadUserInfo() {
        if (mUserInfoTask != null) {
            mUserInfoTask.cancel(false);
            mUserInfoTask = null;
        }
        if (mTilesSetUp) {
            queryForUserInformation();
            queryForSslCaCerts();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (Intent.ACTION_USER_SWITCHED.equals(action)) {
				reloadUserInfo();
			} else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
				if (mUseDefaultAvatar) {
					queryForUserInformation();
				}
			} else if (KeyChain.ACTION_STORAGE_CHANGED.equals(action)) {
				queryForSslCaCerts();
			}
		}
	};

    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ContactsContract.Intents.ACTION_PROFILE_CHANGED.equals(action) ||
                    Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                try {
                    final int currentUser = ActivityManagerNative.getDefault().getCurrentUser().id;
                    final int changedUser =
                            intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
                    if (changedUser == currentUser) {
                        reloadUserInfo();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Couldn't get current user id for profile change", e);
                }
            }

        }
    };
    
    private final BroadcastReceiver mHiveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if(intent.getStringExtra("do").equals("login") || intent.getStringExtra("do").equals("logout")) {
                updateHiveInfo();
          }

        }
    };

    private abstract static class NetworkActivityCallback
            implements QuickSettingsModel.RefreshCallback {
        private final long mDefaultDuration = new ValueAnimator().getDuration();
        private final long mShortDuration = mDefaultDuration / 3;

        public void setActivity(View view, ActivityState state) {
            setVisibility(view.findViewById(R.id.activity_in), state.activityIn);
            setVisibility(view.findViewById(R.id.activity_out), state.activityOut);
        }

        private void setVisibility(View view, boolean visible) {
            final float newAlpha = visible ? 1 : 0;
            if (view.getAlpha() != newAlpha) {
                view.animate()
                    .setDuration(visible ? mShortDuration : mDefaultDuration)
                    .alpha(newAlpha)
                    .start();
            }
        }
    }

	public void updateHiveInfo() {
		readInformation();
		mModel.refreshUserTile();
	}
}

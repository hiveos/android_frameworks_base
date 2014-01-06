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

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.systemui.R;
import com.android.systemui.settings.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;

import java.util.List;

class QuickSettingsModel implements BatteryStateChangeCallback, BrightnessStateChangeCallback
		 {

	// Sett InputMethoManagerService
	private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

	/** Represents the state of a given attribute. */
	static class State {
		int iconId;
		String label;
		boolean enabled = false;
	}

	static class BatteryState extends State {
		int batteryLevel;
		boolean pluggedIn;
	}

	static class ActivityState extends State {
		boolean activityIn;
		boolean activityOut;
	}

	static class RSSIState extends ActivityState {
		int signalIconId;
		String signalContentDescription;
		int dataTypeIconId;
		String dataContentDescription;
	}

	static class WifiState extends ActivityState {
		String signalContentDescription;
		boolean connected;
	}

	static class UserState extends State {
		Drawable avatar;
	}

	static class BrightnessState extends State {
		boolean autoBrightness;
	}

	public static class BluetoothState extends State {
		boolean connected = false;
		String stateContentDescription;
	}

	/** The callback to update a given tile. */
	interface RefreshCallback {
		public void refreshView(QuickSettingsTileView view, State state);
	}

	public static class BasicRefreshCallback implements RefreshCallback {
		private final QuickSettingsBasicTile mView;
		private boolean mShowWhenEnabled;

		public BasicRefreshCallback(QuickSettingsBasicTile v) {
			mView = v;
		}

		public void refreshView(QuickSettingsTileView ignored, State state) {
			if (mShowWhenEnabled) {
				mView.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
			}
			if (state.iconId != 0) {
				mView.setImageDrawable(null); // needed to flush any cached IDs
				mView.setImageResource(state.iconId);
			}
			if (state.label != null) {
				mView.setText(state.label);
			}
		}

		public BasicRefreshCallback setShowWhenEnabled(boolean swe) {
			mShowWhenEnabled = swe;
			return this;
		}
	}

	
	/** ContentObserver to watch brightness **/
	private class BrightnessObserver extends ContentObserver {
		public BrightnessObserver(Handler handler) {
			super(handler);
		}

		@Override
		public void onChange(boolean selfChange) {
			onBrightnessLevelChanged();
		}

		public void startObserving() {
			final ContentResolver cr = mContext.getContentResolver();
			cr.unregisterContentObserver(this);
			cr.registerContentObserver(Settings.System
					.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false,
					this, mUserTracker.getCurrentUserId());
			cr.registerContentObserver(Settings.System
					.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, this,
					mUserTracker.getCurrentUserId());
		}
	}

	private final Context mContext;
	private final Handler mHandler;
	private final CurrentUserTracker mUserTracker;
	private final BrightnessObserver mBrightnessObserver;

	private QuickSettingsTileView mUserTile;
	private RefreshCallback mUserCallback;
	private UserState mUserState = new UserState();

	private QuickSettingsTileView mTimeTile;
	private RefreshCallback mTimeCallback;
	private State mTimeState = new State();

	private QuickSettingsTileView mBatteryTile;
	private RefreshCallback mBatteryCallback;
	private BatteryState mBatteryState = new BatteryState();

	private QuickSettingsTileView mBrightnessTile;
	private RefreshCallback mBrightnessCallback;
	private BrightnessState mBrightnessState = new BrightnessState();

	private QuickSettingsTileView mSettingsTile;
	private RefreshCallback mSettingsCallback;
	private State mSettingsState = new State();

	private QuickSettingsTileView mSslCaCertWarningTile;
	private RefreshCallback mSslCaCertWarningCallback;
	private State mSslCaCertWarningState = new State();


	public QuickSettingsModel(Context context) {
		mContext = context;
		mHandler = new Handler();
		mUserTracker = new CurrentUserTracker(mContext) {
			public void onUserSwitched(int newUserId) {
				mBrightnessObserver.startObserving();
				onBrightnessLevelChanged();
			}
		};

		mBrightnessObserver = new BrightnessObserver(mHandler);
		mBrightnessObserver.startObserving();

		ConnectivityManager cm = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);

	}

	void updateResources() {
		refreshSettingsTile();
		refreshBatteryTile();
		refreshBrightnessTile();
	}

	// Settings
	void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
		mSettingsTile = view;
		mSettingsCallback = cb;
		refreshSettingsTile();
	}

	public void refreshSettingsTile() {
		Resources r = mContext.getResources();
		mSettingsState.label = r
				.getString(R.string.quick_settings_settings_label);
		mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
	}

	// User
	void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
		mUserTile = view;
		mUserCallback = cb;
		mUserCallback.refreshView(mUserTile, mUserState);
	}

	void setUserTileInfo(String name, Drawable avatar) {
		mUserState.label = name;
		mUserState.avatar = avatar;
		mUserCallback.refreshView(mUserTile, mUserState);
	}

	void refreshUserTile() {
		mUserCallback.refreshView(mUserTile, mUserState);
	}

	// Time
	void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
		mTimeTile = view;
		mTimeCallback = cb;
		mTimeCallback.refreshView(view, mTimeState);
	}

	// Battery
	void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
		mBatteryTile = view;
		mBatteryCallback = cb;
		mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
	}

	// BatteryController callback
	@Override
	public void onBatteryLevelChanged(int level, boolean pluggedIn) {
		mBatteryState.batteryLevel = level;
		mBatteryState.pluggedIn = pluggedIn;
		mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
	}

	void refreshBatteryTile() {
		mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
	}

	// Brightness
	void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {
		mBrightnessTile = view;
		mBrightnessCallback = cb;
		onBrightnessLevelChanged();
	}

	@Override
	public void onBrightnessLevelChanged() {
		Resources r = mContext.getResources();
		int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
				Settings.System.SCREEN_BRIGHTNESS_MODE,
				Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
				mUserTracker.getCurrentUserId());
		mBrightnessState.autoBrightness = (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
		mBrightnessState.iconId = mBrightnessState.autoBrightness ? R.drawable.ic_qs_brightness_auto_on
				: R.drawable.ic_qs_brightness_auto_off;
		mBrightnessState.label = r
				.getString(R.string.quick_settings_brightness_label);
		mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
	}

	void refreshBrightnessTile() {
		onBrightnessLevelChanged();
	}

	// SSL CA Cert warning.
	public void addSslCaCertWarningTile(QuickSettingsTileView view,
			RefreshCallback cb) {
		mSslCaCertWarningTile = view;
		mSslCaCertWarningCallback = cb;
		// Set a sane default while we wait for the AsyncTask to finish (no
		// cert).
		setSslCaCertWarningTileInfo(false, true);
	}

	public void setSslCaCertWarningTileInfo(boolean hasCert, boolean isManaged) {
		Resources r = mContext.getResources();
		mSslCaCertWarningState.enabled = hasCert;
		if (isManaged) {
			mSslCaCertWarningState.iconId = R.drawable.ic_qs_certificate_info;
		} else {
			mSslCaCertWarningState.iconId = android.R.drawable.stat_notify_error;
		}
		mSslCaCertWarningState.label = r
				.getString(R.string.ssl_ca_cert_warning);
		mSslCaCertWarningCallback.refreshView(mSslCaCertWarningTile,
				mSslCaCertWarningState);
	}
}

/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Color;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.cherish.header.StatusBarHeaderMachine;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.animation.PhysicsAnimator;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link BaseStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout implements
        StatusBarHeaderMachine.IStatusBarHeaderMachineObserver, TunerService.Tunable {

    private static final String STATUS_BAR_CUSTOM_HEADER_SHADOW =
            "system:" + Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW;

    private static final String QS_PANEL_BG_ALPHA =
            "system:" + Settings.System.QS_PANEL_BG_ALPHA;
    private static final String QS_SB_BG_GRADIENT =
            "system:" + Settings.System.QS_SB_BG_GRADIENT;
    private static final String QS_SB_BG_ALPHA =
            "system:" + Settings.System.QS_SB_BG_ALPHA;

    private final Point mSizePoint = new Point();
    private static final FloatPropertyCompat<QSContainerImpl> BACKGROUND_BOTTOM =
            new FloatPropertyCompat<QSContainerImpl>("backgroundBottom") {
                @Override
                public float getValue(QSContainerImpl qsImpl) {
                    return qsImpl.getBackgroundBottom();
                }

                @Override
                public void setValue(QSContainerImpl background, float value) {
                    background.setBackgroundBottom((int) value);
                }
            };
    private static final PhysicsAnimator.SpringConfig BACKGROUND_SPRING
            = new PhysicsAnimator.SpringConfig(SpringForce.STIFFNESS_MEDIUM,
            SpringForce.DAMPING_RATIO_LOW_BOUNCY);
    private int mBackgroundBottom = -1;
    private int mHeightOverride = -1;
    private QSPanel mQSPanel;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private View mQSPanelContainer;

    private View mBackground;
    private View mBackgroundGradient;
    private View mStatusBarBackground;

    private int mSideMargins;
    private boolean mQsDisabled;
    private int mContentPaddingStart = -1;
    private int mContentPaddingEnd = -1;
    private boolean mAnimateBottomOnNextLayout;

    private boolean mHeaderImageEnabled;
    private ImageView mBackgroundImage;
    private StatusBarHeaderMachine mStatusBarHeaderMachine;
    private Drawable mCurrentBackground;
    private boolean mLandscape;
    private int mHeaderShadow = 0;

    private int mQsBackgroundAlpha = 255;
    private boolean mQsSBBackgroundGradient = true;
    private int mQsSBBackgroundAlpha = 255;
    private int mHeaderImageHeight;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        mStatusBarHeaderMachine = new StatusBarHeaderMachine(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanel = findViewById(R.id.quick_settings_panel);
        mQSPanelContainer = findViewById(R.id.expanded_qs_scroll_view);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        mBackground = findViewById(R.id.quick_settings_background);
        mStatusBarBackground = findViewById(R.id.quick_settings_status_bar_background);
        mBackgroundGradient = findViewById(R.id.quick_settings_gradient_view);
        mBackgroundImage = findViewById(R.id.qs_header_image_view);
        mBackgroundImage.setClipToOutline(true);
        updateSettings();
        updateResources();
        mHeader.getHeaderQsPanel().setMediaVisibilityChangedListener((visible) -> {
            if (mHeader.getHeaderQsPanel().isShown()) {
                mAnimateBottomOnNextLayout = true;
            }
        });
        mQSPanel.setMediaVisibilityChangedListener((visible) -> {
            if (mQSPanel.isShown()) {
                mAnimateBottomOnNextLayout = true;
            }
        });


        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void setBackgroundBottom(int value) {
        // We're saving the bottom separately since otherwise the bottom would be overridden in
        // the layout and the animation wouldn't properly start at the old position.
        mBackgroundBottom = value;
        mBackground.setBottom(value);
    }

    private float getBackgroundBottom() {
        if (mBackgroundBottom == -1) {
            return mBackground.getBottom();
        }
        return mBackgroundBottom;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, STATUS_BAR_CUSTOM_HEADER_SHADOW);
        tunerService.addTunable(this, QS_PANEL_BG_ALPHA);
        tunerService.addTunable(this, QS_SB_BG_GRADIENT);
        tunerService.addTunable(this, QS_SB_BG_ALPHA);

        mStatusBarHeaderMachine.addObserver(this);
        mStatusBarHeaderMachine.updateEnablement();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarHeaderMachine.removeObserver(this);
        Dependency.get(TunerService.class).removeTunable(this);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

        updateResources();
        updateSettings();
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case STATUS_BAR_CUSTOM_HEADER_SHADOW:
                mHeaderShadow =
                        TunerService.parseInteger(newValue, 0);
                applyHeaderBackgroundShadow();
                break;
            case QS_PANEL_BG_ALPHA:
                mQsBackgroundAlpha =
                        TunerService.parseInteger(newValue, 255);
                updateAlpha();
                break;
            case QS_SB_BG_GRADIENT:
                mQsSBBackgroundGradient =
                        TunerService.parseIntegerSwitch(newValue, true);
                updateStatusbarVisibility();
                break;
            case QS_SB_BG_ALPHA:
                mQsSBBackgroundAlpha =
                        TunerService.parseInteger(newValue, 255);
                updateAlpha();
                break;
            default:
                break;
        }
    }

    private void updateAlpha() {
        mBackground.getBackground().setAlpha(mQsSBBackgroundAlpha);
        mStatusBarBackground.getBackground().setAlpha(mQsSBBackgroundAlpha);
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        Configuration config = getResources().getConfiguration();
        boolean navBelow = config.smallestScreenWidthDp >= 600
                || config.orientation != Configuration.ORIENTATION_LANDSCAPE;
        MarginLayoutParams layoutParams = (MarginLayoutParams) mQSPanelContainer.getLayoutParams();

        // The footer is pinned to the bottom of QSPanel (same bottoms), therefore we don't need to
        // subtract its height. We do not care if the collapsed notifications fit in the screen.
        int maxQs = getDisplayHeight() - layoutParams.topMargin - layoutParams.bottomMargin
                - getPaddingBottom();
        if (navBelow) {
            maxQs -= getResources().getDimensionPixelSize(R.dimen.navigation_bar_height);
        }

        int padding = mPaddingLeft + mPaddingRight + layoutParams.leftMargin
                + layoutParams.rightMargin;
        final int qsPanelWidthSpec = getChildMeasureSpec(widthMeasureSpec, padding,
                layoutParams.width);
        mQSPanelContainer.measure(qsPanelWidthSpec,
                MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.AT_MOST));
        int width = mQSPanelContainer.getMeasuredWidth() + padding;
        int height = layoutParams.topMargin + layoutParams.bottomMargin
                + mQSPanelContainer.getMeasuredHeight() + getPaddingBottom();
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }


    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        // Do not measure QSPanel again when doing super.onMeasure.
        // This prevents the pages in PagedTileLayout to be remeasured with a different (incorrect)
        // size to the one used for determining the number of rows and then the number of pages.
        if (child != mQSPanelContainer) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion(mAnimateBottomOnNextLayout /* animate */);
        mAnimateBottomOnNextLayout = false;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mBackground.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
    }

    private void updateResources() {
        int topMargin = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) + (mHeaderImageEnabled ?
                mHeaderImageHeight : 0);

        LayoutParams layoutParams = (LayoutParams) mQSPanelContainer.getLayoutParams();
        layoutParams.topMargin  = topMargin;
        mQSPanelContainer.setLayoutParams(layoutParams);

        mSideMargins = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mContentPaddingStart = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_start);
        int newPaddingEnd = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        boolean marginsChanged = newPaddingEnd != mContentPaddingEnd;
        mContentPaddingEnd = newPaddingEnd;
        if (marginsChanged) {
            updatePaddingsAndMargins();
        }

        int statusBarSideMargin = mHeaderImageEnabled ? mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_header_image_side_margin) : 0;

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mStatusBarBackground.getLayoutParams();
        lp.height = topMargin;
        lp.setMargins(statusBarSideMargin, 0, statusBarSideMargin, 0);
        mStatusBarBackground.setLayoutParams(lp);

        updateStatusbarVisibility();
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        updateExpansion(false /* animate */);
    }

    public void updateExpansion(boolean animate) {
        int height = calculateContainerHeight();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + height);
        mBackground.setTop(mQSPanelContainer.getTop());
        updateBackgroundBottom(height, animate);
    }

    private void updateBackgroundBottom(int height, boolean animated) {
        PhysicsAnimator<QSContainerImpl> physicsAnimator = PhysicsAnimator.getInstance(this);
        if (physicsAnimator.isPropertyAnimating(BACKGROUND_BOTTOM) || animated) {
            // An animation is running or we want to animate
            // Let's make sure to set the currentValue again, since the call below might only
            // start in the next frame and otherwise we'd flicker
            BACKGROUND_BOTTOM.setValue(this, BACKGROUND_BOTTOM.getValue(this));
            physicsAnimator.spring(BACKGROUND_BOTTOM, height, BACKGROUND_SPRING).start();
        } else {
            BACKGROUND_BOTTOM.setValue(this, height);
        }

    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        updateExpansion();
    }

    private void updatePaddingsAndMargins() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mStatusBarBackground || view == mBackgroundGradient
                    || view == mQSCustomizer) {
                // Some views are always full width
                continue;
            }
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.rightMargin = mSideMargins;
            lp.leftMargin = mSideMargins;
            if (view == mQSPanelContainer) {
                // QS panel lays out some of its content full width
                mQSPanel.setContentMargins(mContentPaddingStart, mContentPaddingEnd);
            } else if (view == mHeader) {
                // The header contains the QQS panel which needs to have special padding, to
                // visually align them.
                mHeader.setContentMargins(mContentPaddingStart, mContentPaddingEnd);
            } else {
                view.setPaddingRelative(
                        mContentPaddingStart,
                        view.getPaddingTop(),
                        mContentPaddingEnd,
                        view.getPaddingBottom());
            }
        }
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
            public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
                mHeaderImageEnabled = false;
                updateResources();
            }
        });
    }

    @Override
    public void refreshHeader() {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(mCurrentBackground, true);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            mBackgroundImage.setVisibility(View.VISIBLE);
            mCurrentBackground = next;
            setNotificationPanelHeaderBackground(next, force);
            mHeaderImageEnabled = true;
        } else {
            mCurrentBackground = null;
            mBackgroundImage.setVisibility(View.GONE);
            mHeaderImageEnabled = false;
        }
        updateResources();
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mBackgroundImage.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mBackgroundImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mBackgroundImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mBackgroundImage.setImageDrawable(dw);
        }
        applyHeaderBackgroundShadow();
    }

    private void applyHeaderBackgroundShadow() {
        if (mCurrentBackground != null && mBackgroundImage.getDrawable() != null) {
            mBackgroundImage.setImageAlpha(255 - mHeaderShadow);
        }
    }

    private void updateStatusbarVisibility() {
        boolean hideGradient = mLandscape || mHeaderImageEnabled;
        boolean hideStatusbar = mLandscape && !mHeaderImageEnabled;

        mBackgroundGradient.setVisibility(hideGradient || !mQsSBBackgroundGradient ? View.INVISIBLE : View.VISIBLE);
        mStatusBarBackground.setBackgroundColor(hideGradient ? Color.TRANSPARENT : getResources().getColor(R.color.quick_settings_status_bar_background_color));
        mStatusBarBackground.setVisibility(hideStatusbar ? View.INVISIBLE : View.VISIBLE);

        updateAlpha();
        applyHeaderBackgroundShadow();
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            getContext().getContentResolver().registerContentObserver(Settings.System
                            .getUriFor(Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT), false,
                    this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        updateHeaderImageHeight();
        updateResources();
    }

    private void updateHeaderImageHeight() {
        int mImageHeight = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT, 25,
                UserHandle.USER_CURRENT);
        switch (mImageHeight) {
            case 0:
                mHeaderImageHeight = 0;
                break;
            case 1:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_1);
                break;
            case 2:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_2);
                break;
            case 3:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_3);
                break;
            case 4:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_4);
                break;
            case 5:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_5);
                break;
            case 6:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_6);
                break;
            case 7:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_7);
                break;
            case 8:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_8);
                break;
            case 9:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_9);
                break;
            case 10:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_10);
                break;
            case 11:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_11);
                break;
            case 12:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_12);
                break;
            case 13:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_13);
                break;
            case 14:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_14);
                break;
            case 15:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_15);
                break;
            case 16:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_16);
                break;
            case 17:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_17);
                break;
            case 18:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_18);
                break;
            case 19:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_19);
                break;
            case 20:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_20);
                break;
            case 21:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_21);
                break;
            case 22:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_22);
                break;
            case 23:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_23);
                break;
            case 24:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_24);
                break;
            case 25:
            default:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_25);
                break;
            case 26:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_26);
                break;
            case 27:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_27);
                break;
            case 28:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_28);
                break;
            case 29:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_29);
                break;
            case 30:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_30);
                break;
            case 31:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_31);
                break;
            case 32:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_32);
                break;
            case 33:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_33);
                break;
            case 34:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_34);
                break;
            case 35:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_35);
                break;
            case 36:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_36);
                break;
            case 37:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_37);
                break;
            case 38:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_38);
                break;
            case 39:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_39);
                break;
            case 40:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_40);
                break;
            case 41:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_41);
                break;
            case 42:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_42);
                break;
            case 43:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_43);
                break;
            case 44:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_44);
                break;
            case 45:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_45);
                break;
            case 46:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_46);
                break;
            case 47:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_47);
                break;
            case 48:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_48);
                break;
            case 49:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_49);
                break;
            case 50:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_50);
                break;
            case 51:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_51);
                break;
            case 52:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_52);
                break;
            case 53:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_53);
                break;
            case 54:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_54);
                break;
            case 55:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_55);
                break;
            case 56:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_56);
                break;
            case 57:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_57);
                break;
            case 58:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_58);
                break;
            case 59:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_59);
                break;
            case 60:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_60);
                break;
            case 61:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_61);
                break;
            case 62:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_62);
                break;
            case 63:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_63);
                break;
            case 64:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_64);
                break;
            case 65:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_65);
                break;
            case 66:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_66);
                break;
            case 67:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_67);
                break;
            case 68:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_68);
                break;
            case 69:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_69);
                break;
            case 70:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_70);
                break;
            case 71:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_71);
                break;
            case 72:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_72);
                break;
            case 73:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_73);
                break;
            case 74:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_74);
                break;
            case 75:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_75);
                break;
            case 76:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_76);
                break;
            case 77:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_77);
                break;
            case 78:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_78);
                break;
            case 79:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_79);
                break;
            case 80:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_80);
                break;
            case 81:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_81);
                break;
            case 82:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_82);
                break;
            case 83:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_83);
                break;
            case 84:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_84);
                break;
            case 85:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_85);
                break;
            case 86:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_86);
                break;
            case 87:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_87);
                break;
            case 88:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_88);
                break;
            case 89:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_89);
                break;
            case 90:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_90);
                break;
            case 91:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_91);
                break;
            case 92:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_92);
                break;
            case 93:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_93);
                break;
            case 94:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_94);
                break;
            case 95:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_95);
                break;
            case 96:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_96);
                break;
            case 97:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_97);
                break;
            case 98:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_98);
                break;
            case 99:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_99);
                break;
            case 100:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_100);
                break;
        }
    }
}

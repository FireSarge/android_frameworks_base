/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import java.util.WeakHashMap;

import android.animation.PropertyAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.RemoteViews.RemoteView;

@RemoteView
/**
 * A view that displays its children in a stack and allows users to discretely swipe
 * through the children.
 */
public class StackView extends AdapterViewAnimator {
    private final String TAG = "StackView";

    /**
     * Default animation parameters
     */
    private final int DEFAULT_ANIMATION_DURATION = 400;
    private final int MINIMUM_ANIMATION_DURATION = 50;

    /**
     * These specify the different gesture states
     */
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_SLIDE_UP = 1;
    private static final int GESTURE_SLIDE_DOWN = 2;

    /**
     * Specifies how far you need to swipe (up or down) before it
     * will be consider a completed gesture when you lift your finger
     */
    private static final float SWIPE_THRESHOLD_RATIO = 0.35f;
    private static final float SLIDE_UP_RATIO = 0.7f;

    private final WeakHashMap<View, Float> mRotations = new WeakHashMap<View, Float>();

    /**
     * Sentinel value for no current active pointer.
     * Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    /**
     * These variables are all related to the current state of touch interaction
     * with the stack
     */
    private float mInitialY;
    private float mInitialX;
    private int mActivePointerId;
    private int mYVelocity = 0;
    private int mSwipeGestureType = GESTURE_NONE;
    private int mViewHeight;
    private int mSwipeThreshold;
    private int mTouchSlop;
    private int mMaximumVelocity;
    private VelocityTracker mVelocityTracker;

    private static HolographicHelper sHolographicHelper;
    private ImageView mHighlight;
    private StackSlider mStackSlider;
    private boolean mFirstLayoutHappened = false;
    private ViewGroup mAncestorContainingAllChildren = null;
    private int mAncestorHeight = 0;

    public StackView(Context context) {
        super(context);
        initStackView();
    }

    public StackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initStackView();
    }

    private void initStackView() {
        configureViewAnimator(4, 2, false);
        setStaticTransformationsEnabled(true);
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mActivePointerId = INVALID_POINTER;

        mHighlight = new ImageView(getContext());
        mHighlight.setLayoutParams(new LayoutParams(mHighlight));
        addViewInLayout(mHighlight, -1, new LayoutParams(mHighlight));
        mStackSlider = new StackSlider();

        if (sHolographicHelper == null) {
            sHolographicHelper = new HolographicHelper();
        }
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * Animate the views between different relative indexes within the {@link AdapterViewAnimator}
     */
    void animateViewForTransition(int fromIndex, int toIndex, View view) {
        if (fromIndex == -1 && toIndex == 0) {
            // Fade item in
            if (view.getAlpha() == 1) {
                view.setAlpha(0);
            }
            view.setVisibility(VISIBLE);

            PropertyAnimator fadeIn = new PropertyAnimator(DEFAULT_ANIMATION_DURATION,
                    view, "alpha", view.getAlpha(), 1.0f);
            fadeIn.start();
        } else if (fromIndex == mNumActiveViews - 1 && toIndex == mNumActiveViews - 2) {
            // Slide item in
            view.setVisibility(VISIBLE);

            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            int largestDuration =
                Math.round(mStackSlider.getDurationForNeutralPosition()*DEFAULT_ANIMATION_DURATION);

            int duration = largestDuration;
            if (mYVelocity != 0) {
                duration = 1000*(0 - lp.verticalOffset)/Math.abs(mYVelocity);
            }

            duration = Math.min(duration, largestDuration);
            duration = Math.max(duration, MINIMUM_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator slideInY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 0);
            slideInY.setInterpolator(new LinearInterpolator());
            slideInY.start();
            PropertyAnimator slideInX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            slideInX.setInterpolator(new LinearInterpolator());
            slideInX.start();
        } else if (fromIndex == mNumActiveViews - 2 && toIndex == mNumActiveViews - 1) {
            // Slide item out
            LayoutParams lp = (LayoutParams) view.getLayoutParams();

            int largestDuration = Math.round(mStackSlider.getDurationForOffscreenPosition()*
                    DEFAULT_ANIMATION_DURATION);
            int duration = largestDuration;
            if (mYVelocity != 0) {
                duration = 1000*(lp.verticalOffset + mViewHeight)/Math.abs(mYVelocity);
            }

            duration = Math.min(duration, largestDuration);
            duration = Math.max(duration, MINIMUM_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator slideOutY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 1);
            slideOutY.setInterpolator(new LinearInterpolator());
            slideOutY.start();
            PropertyAnimator slideOutX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            slideOutX.setInterpolator(new LinearInterpolator());
            slideOutX.start();
        } else if (fromIndex == -1 && toIndex == mNumActiveViews - 1) {
            // Make sure this view that is "waiting in the wings" is invisible
            view.setAlpha(0.0f);
            view.setVisibility(INVISIBLE);
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.setVerticalOffset(-mViewHeight);
        } else if (toIndex == -1) {
            // Fade item out
            PropertyAnimator fadeOut = new PropertyAnimator(DEFAULT_ANIMATION_DURATION,
                    view, "alpha", view.getAlpha(), 0);
            fadeOut.start();
        }
    }

    /**
     * Apply any necessary tranforms for the child that is being added.
     */
    void applyTransformForChildAtIndex(View child, int relativeIndex) {
        if (!mRotations.containsKey(child)) {
            float rotation = (float) (Math.random()*26 - 13);
            mRotations.put(child, rotation);
            child.setRotation(rotation);
        }

        // Child has been removed
        if (relativeIndex == -1) {
            if (mRotations.containsKey(child)) {
                mRotations.remove(child);
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    // TODO: right now, this code walks up the hierarchy as far as needed and disables clipping
    // so that the stack's children can draw outside of the stack's bounds. This is fine within
    // the context of widgets in the launcher, but is destructive in general, as the clipping
    // values are not being reset. For this to be a full framework level widget, we will need
    // framework level support for drawing outside of a parent's bounds.
    private void disableParentalClipping() {
        if (mAncestorContainingAllChildren != null) {
            Log.v(TAG, "Disabling parental clipping.");
            ViewGroup vg = this;
            while (vg.getParent() != null && vg.getParent() instanceof ViewGroup) {
                if (vg == mAncestorContainingAllChildren) break;
                vg = (ViewGroup) vg.getParent();
                vg.setClipChildren(false);
                vg.setClipToPadding(false);
            }
        }
    }

    private void onLayout() {
        if (!mFirstLayoutHappened) {
            mViewHeight = Math.round(SLIDE_UP_RATIO*getMeasuredHeight());
            mSwipeThreshold = Math.round(SWIPE_THRESHOLD_RATIO*mViewHeight);
            mFirstLayoutHappened = true;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getAction();
        switch(action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                if (mActivePointerId == INVALID_POINTER) {
                    mInitialX = ev.getX();
                    mInitialY = ev.getY();
                    mActivePointerId = ev.getPointerId(0);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex == INVALID_POINTER) {
                    // no data for our primary pointer, this shouldn't happen, log it
                    Log.d(TAG, "Error: No data for our primary pointer.");
                    return false;
                }
                float newY = ev.getY(pointerIndex);
                float deltaY = newY - mInitialY;

                beginGestureIfNeeded(deltaY);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = INVALID_POINTER;
                mSwipeGestureType = GESTURE_NONE;
            }
        }

        return mSwipeGestureType != GESTURE_NONE;
    }

    private void beginGestureIfNeeded(float deltaY) {
        if ((int) Math.abs(deltaY) > mTouchSlop && mSwipeGestureType == GESTURE_NONE) {
            int swipeGestureType = deltaY < 0 ? GESTURE_SLIDE_UP : GESTURE_SLIDE_DOWN;
            cancelLongPress();
            requestDisallowInterceptTouchEvent(true);

            int activeIndex = swipeGestureType == GESTURE_SLIDE_DOWN ? mNumActiveViews - 1
                    : mNumActiveViews - 2;

            if (mAdapter == null) return;

            if (mCurrentWindowStartUnbounded + activeIndex == 0) {
                mStackSlider.setMode(StackSlider.BEGINNING_OF_STACK_MODE);
            } else if (mCurrentWindowStartUnbounded + activeIndex == mAdapter.getCount()) {
                activeIndex--;
                mStackSlider.setMode(StackSlider.END_OF_STACK_MODE);
            } else {
                mStackSlider.setMode(StackSlider.NORMAL_MODE);
            }

            View v = getViewAtRelativeIndex(activeIndex);
            if (v == null) return;

            mHighlight.setImageBitmap(sHolographicHelper.createOutline(v));
            mHighlight.bringToFront();
            v.bringToFront();
            mStackSlider.setView(v);

            if (swipeGestureType == GESTURE_SLIDE_DOWN)
                v.setVisibility(VISIBLE);

            // We only register this gesture if we've made it this far without a problem
            mSwipeGestureType = swipeGestureType;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getAction();
        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex == INVALID_POINTER) {
            // no data for our primary pointer, this shouldn't happen, log it
            Log.d(TAG, "Error: No data for our primary pointer.");
            return false;
        }

        float newY = ev.getY(pointerIndex);
        float newX = ev.getX(pointerIndex);
        float deltaY = newY - mInitialY;
        float deltaX = newX - mInitialX;
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                beginGestureIfNeeded(deltaY);

                float rx = deltaX/(mViewHeight*1.0f);
                if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
                    float r = (deltaY-mTouchSlop*1.0f)/mViewHeight*1.0f;
                    mStackSlider.setYProgress(1 - r);
                    mStackSlider.setXProgress(rx);
                    return true;
                } else if (mSwipeGestureType == GESTURE_SLIDE_UP) {
                    float r = -(deltaY + mTouchSlop*1.0f)/mViewHeight*1.0f;
                    mStackSlider.setYProgress(r);
                    mStackSlider.setXProgress(rx);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                handlePointerUp(ev);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = INVALID_POINTER;
                mSwipeGestureType = GESTURE_NONE;
                break;
            }
        }
        return true;
    }

    private final Rect touchRect = new Rect();
    private void onSecondaryPointerUp(MotionEvent ev) {
        final int activePointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(activePointerIndex);
        if (pointerId == mActivePointerId) {

            int activeViewIndex = (mSwipeGestureType == GESTURE_SLIDE_DOWN) ? mNumActiveViews - 1
                    : mNumActiveViews - 2;

            View v = getViewAtRelativeIndex(activeViewIndex);
            if (v == null) return;

            // Our primary pointer has gone up -- let's see if we can find
            // another pointer on the view. If so, then we should replace
            // our primary pointer with this new pointer and adjust things
            // so that the view doesn't jump
            for (int index = 0; index < ev.getPointerCount(); index++) {
                if (index != activePointerIndex) {

                    float x = ev.getX(index);
                    float y = ev.getY(index);

                    touchRect.set(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                    if (touchRect.contains(Math.round(x), Math.round(y))) {
                        float oldX = ev.getX(activePointerIndex);
                        float oldY = ev.getY(activePointerIndex);

                        // adjust our frame of reference to avoid a jump
                        mInitialY += (y - oldY);
                        mInitialX += (x - oldX);

                        mActivePointerId = ev.getPointerId(index);
                        if (mVelocityTracker != null) {
                            mVelocityTracker.clear();
                        }
                        // ok, we're good, we found a new pointer which is touching the active view
                        return;
                    }
                }
            }
            // if we made it this far, it means we didn't find a satisfactory new pointer :(,
            // so end the gesture
            handlePointerUp(ev);
        }
    }

    private void handlePointerUp(MotionEvent ev) {
        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        float newY = ev.getY(pointerIndex);
        int deltaY = (int) (newY - mInitialY);

        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
            mYVelocity = (int) mVelocityTracker.getYVelocity(mActivePointerId);
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        if (deltaY > mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_DOWN
                && mStackSlider.mMode == StackSlider.NORMAL_MODE) {
            // Swipe threshold exceeded, swipe down
            showNext();
            mHighlight.bringToFront();
        } else if (deltaY < -mSwipeThreshold && mSwipeGestureType == GESTURE_SLIDE_UP
                && mStackSlider.mMode == StackSlider.NORMAL_MODE) {
            // Swipe threshold exceeded, swipe up
            showPrevious();
            mHighlight.bringToFront();
        } else if (mSwipeGestureType == GESTURE_SLIDE_UP) {
            // Didn't swipe up far enough, snap back down
            int duration =
                Math.round(mStackSlider.getDurationForNeutralPosition()*DEFAULT_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator snapBackY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 0);
            snapBackY.setInterpolator(new LinearInterpolator());
            snapBackY.start();
            PropertyAnimator snapBackX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            snapBackX.setInterpolator(new LinearInterpolator());
            snapBackX.start();
        } else if (mSwipeGestureType == GESTURE_SLIDE_DOWN) {
            // Didn't swipe down far enough, snap back up
            int duration = Math.round(mStackSlider.getDurationForOffscreenPosition()*
                    DEFAULT_ANIMATION_DURATION);

            StackSlider animationSlider = new StackSlider(mStackSlider);
            PropertyAnimator snapBackY = new PropertyAnimator(duration, animationSlider,
                    "YProgress", mStackSlider.getYProgress(), 1);
            snapBackY.setInterpolator(new LinearInterpolator());
            snapBackY.start();
            PropertyAnimator snapBackX = new PropertyAnimator(duration, animationSlider,
                    "XProgress", mStackSlider.getXProgress(), 0);
            snapBackX.setInterpolator(new LinearInterpolator());
            snapBackX.start();
        }

        mActivePointerId = INVALID_POINTER;
        mSwipeGestureType = GESTURE_NONE;
    }

    private class StackSlider {
        View mView;
        float mYProgress;
        float mXProgress;

        static final int NORMAL_MODE = 0;
        static final int BEGINNING_OF_STACK_MODE = 1;
        static final int END_OF_STACK_MODE = 2;

        int mMode = NORMAL_MODE;

        public StackSlider() {
        }

        public StackSlider(StackSlider copy) {
            mView = copy.mView;
            mYProgress = copy.mYProgress;
            mXProgress = copy.mXProgress;
            mMode = copy.mMode;
        }

        private float cubic(float r) {
            return (float) (Math.pow(2*r-1, 3) + 1)/2.0f;
        }

        private float highlightAlphaInterpolator(float r) {
            float pivot = 0.4f;
            if (r < pivot) {
                return 0.85f*cubic(r/pivot);
            } else {
                return 0.85f*cubic(1 - (r-pivot)/(1-pivot));
            }
        }

        private float viewAlphaInterpolator(float r) {
            float pivot = 0.3f;
            if (r > pivot) {
                return (r - pivot)/(1 - pivot);
            } else {
                return 0;
            }
        }

        private float rotationInterpolator(float r) {
            float pivot = 0.2f;
            if (r < pivot) {
                return 0;
            } else {
                return (r-pivot)/(1-pivot);
            }
        }

        void setView(View v) {
            mView = v;
        }

        public void setYProgress(float r) {
            // enforce r between 0 and 1
            r = Math.min(1.0f, r);
            r = Math.max(0, r);

            mYProgress = r;
            final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();
            final LayoutParams highlightLp = (LayoutParams) mHighlight.getLayoutParams();

            switch (mMode) {
                case NORMAL_MODE:
                    viewLp.setVerticalOffset(Math.round(-r*mViewHeight));
                    highlightLp.setVerticalOffset(Math.round(-r*mViewHeight));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));

                    float alpha = viewAlphaInterpolator(1-r);

                    // We make sure that views which can't be seen (have 0 alpha) are also invisible
                    // so that they don't interfere with click events.
                    if (mView.getAlpha() == 0 && alpha != 0 && mView.getVisibility() != VISIBLE) {
                        mView.setVisibility(VISIBLE);
                    } else if (alpha == 0 && mView.getAlpha() != 0
                            && mView.getVisibility() == VISIBLE) {
                        mView.setVisibility(INVISIBLE);
                    }

                    mView.setAlpha(alpha);
                    mView.setRotationX(90.0f*rotationInterpolator(r));
                    mHighlight.setRotationX(90.0f*rotationInterpolator(r));
                    break;
                case BEGINNING_OF_STACK_MODE:
                    r = r*0.2f;
                    viewLp.setVerticalOffset(Math.round(-r*mViewHeight));
                    highlightLp.setVerticalOffset(Math.round(-r*mViewHeight));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));
                    break;
                case END_OF_STACK_MODE:
                    r = (1-r)*0.2f;
                    viewLp.setVerticalOffset(Math.round(r*mViewHeight));
                    highlightLp.setVerticalOffset(Math.round(r*mViewHeight));
                    mHighlight.setAlpha(highlightAlphaInterpolator(r));
                    break;
            }
        }

        public void setXProgress(float r) {
            // enforce r between 0 and 1
            r = Math.min(2.0f, r);
            r = Math.max(-2.0f, r);

            mXProgress = r;

            final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();
            final LayoutParams highlightLp = (LayoutParams) mHighlight.getLayoutParams();

            r *= 0.2f;
            viewLp.setHorizontalOffset(Math.round(r*mViewHeight));
            highlightLp.setHorizontalOffset(Math.round(r*mViewHeight));
        }

        void setMode(int mode) {
            mMode = mode;
        }

        float getDurationForNeutralPosition() {
            return getDuration(false);
        }

        float getDurationForOffscreenPosition() {
            return getDuration(mMode == END_OF_STACK_MODE ? false : true);
        }

        private float getDuration(boolean invert) {
            if (mView != null) {
                final LayoutParams viewLp = (LayoutParams) mView.getLayoutParams();

                float d = (float) Math.sqrt(Math.pow(viewLp.horizontalOffset,2) +
                        Math.pow(viewLp.verticalOffset,2));
                float maxd = (float) Math.sqrt(Math.pow(mViewHeight, 2) +
                        Math.pow(0.4f*mViewHeight, 2));
                return invert ? (1-d/maxd) : d/maxd;
            }
            return 0;
        }

        float getYProgress() {
            return mYProgress;
        }

        float getXProgress() {
            return mXProgress;
        }
    }

    @Override
    public void onRemoteAdapterConnected() {
        super.onRemoteAdapterConnected();
        setDisplayedChild(mWhichChild);
    }

    LayoutParams createOrReuseLayoutParams(View v) {
        final ViewGroup.LayoutParams currentLp = v.getLayoutParams();
        if (currentLp instanceof LayoutParams) {
            LayoutParams lp = (LayoutParams) currentLp;
            lp.setHorizontalOffset(0);
            lp.setVerticalOffset(0);
            return lp;
        }
        return new LayoutParams(v);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean dataChanged = mDataChanged;
        if (dataChanged) {
            handleDataChanged();

            // if the data changes, mWhichChild might be out of the bounds of the adapter
            // in this case, we reset mWhichChild to the beginning
            if (mWhichChild >= mAdapter.getCount())
                mWhichChild = 0;

            showOnly(mWhichChild, true, true);
        }

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            int childRight = mPaddingLeft + child.getMeasuredWidth();
            int childBottom = mPaddingTop + child.getMeasuredHeight();
            LayoutParams lp = (LayoutParams) child.getLayoutParams();

            child.layout(mPaddingLeft + lp.horizontalOffset, mPaddingTop + lp.verticalOffset,
                    childRight + lp.horizontalOffset, childBottom + lp.verticalOffset);

            //TODO: temp until fix in View
            child.setPivotX(child.getMeasuredWidth()/2);
            child.setPivotY(child.getMeasuredHeight()/2);
        }

        mDataChanged = false;
        onLayout();
    }

    class LayoutParams extends ViewGroup.LayoutParams {
        int horizontalOffset;
        int verticalOffset;
        View mView;

        LayoutParams(View view) {
            super(0, 0);
            horizontalOffset = 0;
            verticalOffset = 0;
            mView = view;
        }

        LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            horizontalOffset = 0;
            verticalOffset = 0;
        }

        private Rect parentRect = new Rect();
        void invalidateGlobalRegion(View v, Rect r) {
            View p = v;
            if (!(v.getParent() != null && v.getParent() instanceof View)) return;

            View gp = (View) v.getParent();
            boolean firstPass = true;
            parentRect.set(0, 0, 0, 0);
            int depth = 0;
            while (gp.getParent() != null && gp.getParent() instanceof View
                    && !parentRect.contains(r)) {
                if (!firstPass) {
                    r.offset(p.getLeft() - gp.getScrollX(), p.getTop() - gp.getScrollY());
                    depth++;
                }
                firstPass = false;
                p = (View) p.getParent();
                gp = (View) p.getParent();
                parentRect.set(p.getLeft() - gp.getScrollX(), p.getTop() - gp.getScrollY(),
                        p.getRight() - gp.getScrollX(), p.getBottom() - gp.getScrollY());
            }

            if (depth > mAncestorHeight) {
                mAncestorContainingAllChildren = (ViewGroup) p;
                mAncestorHeight = depth;
                disableParentalClipping();
            }

            p.invalidate(r.left, r.top, r.right, r.bottom);
        }

        private Rect invalidateRect = new Rect();
        private RectF invalidateRectf = new RectF();
        // This is public so that PropertyAnimator can access it
        public void setVerticalOffset(int newVerticalOffset) {
            int offsetDelta = newVerticalOffset - verticalOffset;
            verticalOffset = newVerticalOffset;

            if (mView != null) {
                mView.requestLayout();
                int top = Math.min(mView.getTop() + offsetDelta, mView.getTop());
                int bottom = Math.max(mView.getBottom() + offsetDelta, mView.getBottom());

                invalidateRectf.set(mView.getLeft(),  top, mView.getRight(), bottom);

                float xoffset = -invalidateRectf.left;
                float yoffset = -invalidateRectf.top;
                invalidateRectf.offset(xoffset, yoffset);
                mView.getMatrix().mapRect(invalidateRectf);
                invalidateRectf.offset(-xoffset, -yoffset);
                invalidateRect.set((int) Math.floor(invalidateRectf.left),
                        (int) Math.floor(invalidateRectf.top),
                        (int) Math.ceil(invalidateRectf.right),
                        (int) Math.ceil(invalidateRectf.bottom));

                invalidateGlobalRegion(mView, invalidateRect);
            }
        }

        public void setHorizontalOffset(int newHorizontalOffset) {
            int offsetDelta = newHorizontalOffset - horizontalOffset;
            horizontalOffset = newHorizontalOffset;

            if (mView != null) {
                mView.requestLayout();
                int left = Math.min(mView.getLeft() + offsetDelta, mView.getLeft());
                int right = Math.max(mView.getRight() + offsetDelta, mView.getRight());
                invalidateRectf.set(left,  mView.getTop(), right, mView.getBottom());

                float xoffset = -invalidateRectf.left;
                float yoffset = -invalidateRectf.top;
                invalidateRectf.offset(xoffset, yoffset);
                mView.getMatrix().mapRect(invalidateRectf);
                invalidateRectf.offset(-xoffset, -yoffset);

                invalidateRect.set((int) Math.floor(invalidateRectf.left),
                        (int) Math.floor(invalidateRectf.top),
                        (int) Math.ceil(invalidateRectf.right),
                        (int) Math.ceil(invalidateRectf.bottom));

                invalidateGlobalRegion(mView, invalidateRect);
            }
        }
    }

    private static class HolographicHelper {
        private final Paint mHolographicPaint = new Paint();
        private final Paint mErasePaint = new Paint();
        private final float STROKE_WIDTH = 3.0f;

        HolographicHelper() {
            initializePaints();
        }

        void initializePaints() {
            mHolographicPaint.setColor(0xff6699ff);
            mHolographicPaint.setFilterBitmap(true);
            mErasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            mErasePaint.setFilterBitmap(true);
        }

        Bitmap createOutline(View v) {
            if (v.getMeasuredWidth() == 0 || v.getMeasuredHeight() == 0) {
                return null;
            }

            Bitmap bitmap = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            float rotationX = v.getRotationX();
            v.setRotationX(0);
            canvas.concat(v.getMatrix());
            v.draw(canvas);

            v.setRotationX(rotationX);

            drawOutline(canvas, bitmap);
            return bitmap;
        }

        final Matrix id = new Matrix();
        final Matrix scaleMatrix = new Matrix();
        void drawOutline(Canvas dest, Bitmap src) {
            Bitmap mask = src.extractAlpha();

            dest.drawColor(0, PorterDuff.Mode.CLEAR);

            float xScale = STROKE_WIDTH*2/(dest.getWidth());
            float yScale = STROKE_WIDTH*2/(dest.getHeight());

            scaleMatrix.reset();
            scaleMatrix.preScale(1+xScale, 1+yScale, dest.getWidth()/2, dest.getHeight()/2);
            dest.setMatrix(id);
            dest.drawBitmap(mask, scaleMatrix, mHolographicPaint);
            dest.drawBitmap(mask, id, mErasePaint);
            mask.recycle();
        }
    }
}

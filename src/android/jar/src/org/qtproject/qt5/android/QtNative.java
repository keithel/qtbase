/****************************************************************************
**
** Copyright (C) 2016 BogDan Vatra <bogdan@kde.org>
** Copyright (C) 2016 The Qt Company Ltd.
** Contact: https://www.qt.io/licensing/
**
** This file is part of the Android port of the Qt Toolkit.
**
** $QT_BEGIN_LICENSE:LGPL$
** Commercial License Usage
** Licensees holding valid commercial Qt licenses may use this file in
** accordance with the commercial license agreement provided with the
** Software or, alternatively, in accordance with the terms contained in
** a written agreement between you and The Qt Company. For licensing terms
** and conditions see https://www.qt.io/terms-conditions. For further
** information use the contact form at https://www.qt.io/contact-us.
**
** GNU Lesser General Public License Usage
** Alternatively, this file may be used under the terms of the GNU Lesser
** General Public License version 3 as published by the Free Software
** Foundation and appearing in the file LICENSE.LGPL3 included in the
** packaging of this file. Please review the following information to
** ensure the GNU Lesser General Public License version 3 requirements
** will be met: https://www.gnu.org/licenses/lgpl-3.0.html.
**
** GNU General Public License Usage
** Alternatively, this file may be used under the terms of the GNU
** General Public License version 2.0 or (at your option) the GNU General
** Public license version 3 or any later version approved by the KDE Free
** Qt Foundation. The licenses are as published by the Free Software
** Foundation and appearing in the file LICENSE.GPL2 and LICENSE.GPL3
** included in the packaging of this file. Please review the following
** information to ensure the GNU General Public License requirements will
** be met: https://www.gnu.org/licenses/gpl-2.0.html and
** https://www.gnu.org/licenses/gpl-3.0.html.
**
** $QT_END_LICENSE$
**
****************************************************************************/

package org.qtproject.qt5.android;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.ClipboardManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class QtNative
{
    private static Activity m_activity = null;
    private static boolean m_activityPaused = false;
    private static Service m_service = null;
    private static QtActivityDelegate m_activityDelegate = null;
    private static QtServiceDelegate m_serviceDelegate = null;
    public static Object m_mainActivityMutex = new Object(); // mutex used to synchronize runnable operations

    public static final String QtTAG = "Qt JAVA"; // string used for Log.x
    private static ArrayList<Runnable> m_lostActions = new ArrayList<Runnable>(); // a list containing all actions which could not be performed (e.g. the main activity is destroyed, etc.)
    private static boolean m_started = false;
    private static int m_startingDesktopWidth = 0;
    private static int m_startingDesktopHeight = 0;
    private static int m_oldx, m_oldy;
    private static final int m_moveThreshold = 0;
    private static DisplayManager m_displayManager = null;
    private static DisplayManager.DisplayListener m_displayListener = null;

    private static ClipboardManager m_clipboardManager = null;
    private static Method m_checkSelfPermissionMethod = null;
    private static final Runnable runPendingCppRunnablesRunnable = new Runnable() {
        @Override
        public void run() {
            runPendingCppRunnables();
        }
    };

    private static ClassLoader m_classLoader = null;
    public static ClassLoader classLoader()
    {
        return m_classLoader;
    }

    public static void setClassLoader(ClassLoader classLoader)
    {
            m_classLoader = classLoader;
    }

    public static Activity activity()
    {
        synchronized (m_mainActivityMutex) {
            return m_activity;
        }
    }

    public static Service service()
    {
        synchronized (m_mainActivityMutex) {
            return m_service;
        }
    }

    public static QtActivityDelegate activityDelegate()
    {
        synchronized (m_mainActivityMutex) {
            return m_activityDelegate;
        }
    }

    public static QtServiceDelegate serviceDelegate()
    {
        synchronized (m_mainActivityMutex) {
            return m_serviceDelegate;
        }
    }

    public static boolean openURL(String url, String mime)
    {
        boolean ok = true;

        try {
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (!mime.isEmpty())
                intent.setDataAndType(uri, mime);
            activity().startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
        }

        return ok;
    }

    // this method loads full path libs
    public static void loadQtLibraries(ArrayList<String> libraries)
    {
        if (libraries == null)
            return;

        for (String libName : libraries) {
            try {
                File f = new File(libName);
                if (f.exists())
                    System.load(libName);
            } catch (SecurityException e) {
                Log.i(QtTAG, "Can't load '" + libName + "'", e);
            } catch (Exception e) {
                Log.i(QtTAG, "Can't load '" + libName + "'", e);
            }
        }
    }

    // this method loads bundled libs by name.
    public static void loadBundledLibraries(ArrayList<String> libraries, String nativeLibraryDir)
    {
        if (libraries == null)
            return;

        for (String libName : libraries) {
            try {
                File f = new File(nativeLibraryDir+"lib"+libName+".so");
                if (f.exists())
                    System.load(f.getAbsolutePath());
                else
                    Log.i(QtTAG, "Can't find '" + f.getAbsolutePath());
            } catch (Exception e) {
                Log.i(QtTAG, "Can't load '" + libName + "'", e);
            }
        }
    }

    public static void setActivity(Activity qtMainActivity, QtActivityDelegate qtActivityDelegate)
    {
        synchronized (m_mainActivityMutex) {
            m_activity = qtMainActivity;
            m_activityDelegate = qtActivityDelegate;
        }
    }

    public static void setService(Service qtMainService, QtServiceDelegate qtServiceDelegate)
    {
        synchronized (m_mainActivityMutex) {
            m_service = qtMainService;
            m_serviceDelegate = qtServiceDelegate;
        }
    }

    public static void setApplicationState(int state)
    {
        synchronized (m_mainActivityMutex) {
            switch (state) {
                case QtActivityDelegate.ApplicationActive:
                    m_displayManager.registerDisplayListener(m_displayListener, null);
                    m_activityPaused = false;
                    Iterator<Runnable> itr = m_lostActions.iterator();
                    while (itr.hasNext())
                        runAction(itr.next());
                    m_lostActions.clear();
                    break;
                default:
                    m_activityPaused = true;
                    m_displayManager.unregisterDisplayListener(m_displayListener);
                    break;
            }
        }
        updateApplicationState(state);
    }

    private static void runAction(Runnable action)
    {
        synchronized (m_mainActivityMutex) {
            final Looper mainLooper = Looper.getMainLooper();
            final Handler handler = new Handler(mainLooper);
            final boolean actionIsQueued = !m_activityPaused && m_activity != null && mainLooper != null && handler.post(action);
            if (!actionIsQueued)
                m_lostActions.add(action);
        }
    }

    private static void runPendingCppRunnablesOnUiThread()
    {
        synchronized (m_mainActivityMutex) {
            if (!m_activityPaused && m_activity != null)
                m_activity.runOnUiThread(runPendingCppRunnablesRunnable);
            else
                runAction(runPendingCppRunnablesRunnable);
        }
    }

    private static void setViewVisibility(final View view, final boolean visible)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                view.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    public static boolean startApplication(String params,
                                           String environment,
                                           String mainLibrary,
                                           String nativeLibraryDir) throws Exception
    {
        File f = new File(nativeLibraryDir + "lib" + mainLibrary + ".so");
        if (!f.exists())
            throw new Exception("Can't find main library '" + mainLibrary + "'");

        if (params == null)
            params = "-platform\tandroid";

        boolean res = false;
        synchronized (m_mainActivityMutex) {
            res = startQtAndroidPlugin();
            final ContextWrapper context = m_activity != null ? m_activity : (m_service != null ? m_service : null);

            m_displayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
            m_displayListener = new DisplayManager.DisplayListener()
            {
                // TODO: Tell Qt about added displays...
                @Override
                public void onDisplayAdded(int i)
                {
                    Toast.makeText(context, "Display " + i + " added", Toast.LENGTH_LONG).show();
                    Display display = m_displayManager.getDisplay(i);
                    // TODO: Provide better values for desktop width, height..
                    setApplicationDisplayMetrics(display, m_startingDesktopWidth, m_startingDesktopHeight);
                }

                @Override
                public void onDisplayRemoved(int i)
                {
                    removeDisplay(i);
                    Toast.makeText(context, "Display " + i + " removed", Toast.LENGTH_LONG).show();
                }

                @Override
                public void onDisplayChanged(int i)
                {
                    Toast.makeText(context, "Display " + i + " changed", Toast.LENGTH_LONG).show();
                    Display display = m_displayManager.getDisplay(i);
                    setApplicationDisplayMetrics(display, m_startingDesktopWidth, m_startingDesktopHeight);
                }
            };

            // Register display listener now because ApplicationActive state is already reached
            // (onResume has already been called) at this point.
            m_displayManager.registerDisplayListener(m_displayListener, null);

            for (Display display : m_displayManager.getDisplays()) {
                DisplayMetrics displayMetrics = new DisplayMetrics();
                display.getMetrics(displayMetrics);
                fixDisplayMetricsDpi(displayMetrics);
                setDisplayMetrics(display.getDisplayId(),
                                  display.getName(),
                                  displayMetrics.widthPixels,
                                  displayMetrics.heightPixels,
                                  m_startingDesktopWidth,
                                  m_startingDesktopHeight,
                                  displayMetrics.xdpi,
                                  displayMetrics.ydpi,
                                  displayMetrics.scaledDensity,
                                  displayMetrics.density);
            }
            if (params.length() > 0 && !params.startsWith("\t"))
                params = "\t" + params;
            startQtApplication(f.getAbsolutePath() + params, environment);
            m_started = true;
        }
        return res;
    }

    public static void setApplicationDisplayMetrics(Display display,
                                                    int desktopWidthPixels,
                                                    int desktopHeightPixels)
    {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getMetrics(displayMetrics);
        fixDisplayMetricsDpi(displayMetrics);

        synchronized (m_mainActivityMutex) {
            if (m_started) {
                setDisplayMetrics(display.getDisplayId(),
                                  display.getName(),
                                  displayMetrics.widthPixels,
                                  displayMetrics.heightPixels,
                                  desktopWidthPixels,
                                  desktopHeightPixels,
                                  displayMetrics.xdpi,
                                  displayMetrics.ydpi,
                                  displayMetrics.scaledDensity,
                                  displayMetrics.density);
            } else {
                m_startingDesktopWidth = desktopWidthPixels;
                m_startingDesktopHeight = desktopHeightPixels;
            }
        }
    }

    private static void fixDisplayMetricsDpi(DisplayMetrics displayMetrics)
    {
        /* Fix buggy dpi report */
        if (displayMetrics.xdpi < android.util.DisplayMetrics.DENSITY_LOW)
            displayMetrics.xdpi = android.util.DisplayMetrics.DENSITY_LOW;
        if (displayMetrics.ydpi < android.util.DisplayMetrics.DENSITY_LOW)
            displayMetrics.ydpi = android.util.DisplayMetrics.DENSITY_LOW;
    }



    // application methods
    public static native void startQtApplication(String params, String env);
    public static native boolean startQtAndroidPlugin();
    public static native void quitQtAndroidPlugin();
    public static native void terminateQt();
    // application methods

    private static void quitApp()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                quitQtAndroidPlugin();
                if (m_activity != null)
                     m_activity.finish();
                 if (m_service != null)
                     m_service.stopSelf();
            }
        });
    }

    //@ANDROID-9
    static private int getAction(int index, MotionEvent event)
    {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            int hsz = event.getHistorySize();
            if (hsz > 0) {
                float x = event.getX(index);
                float y = event.getY(index);
                for (int h = 0; h < hsz; ++h) {
                    if ( event.getHistoricalX(index, h) != x ||
                         event.getHistoricalY(index, h) != y )
                        return 1;
                }
                return 2;
            }
            return 1;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN && index == event.getActionIndex()) {
            return 0;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && index == event.getActionIndex()) {
            return 3;
        }
        return 2;
    }
    //@ANDROID-9

    static public void sendTouchEvent(MotionEvent event, int id, int displayId)
    {
        int pointerType = 0;

        switch (event.getToolType(0)) {
        case MotionEvent.TOOL_TYPE_STYLUS:
            pointerType = 1; // QTabletEvent::Pen
            break;
        case MotionEvent.TOOL_TYPE_ERASER:
            pointerType = 3; // QTabletEvent::Eraser
            break;
        // TODO TOOL_TYPE_MOUSE
        }

        if (pointerType != 0) {
            tabletEvent(id, event.getDeviceId(), event.getEventTime(), event.getAction(), pointerType,
                event.getButtonState(), event.getX(), event.getY(), event.getPressure());
        } else {
            touchBegin(id, displayId);
            for (int i = 0; i < event.getPointerCount(); ++i) {
                    touchAdd(id,
                             displayId,
                             event.getPointerId(i),
                             getAction(i, event),
                             i == 0,
                             (int)event.getX(i),
                             (int)event.getY(i),
                             event.getSize(i),
                             event.getPressure(i));
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchEnd(id, displayId, 0);
                    break;

                case MotionEvent.ACTION_UP:
                    touchEnd(id, displayId, 2);
                    break;

                default:
                    touchEnd(id, displayId, 1);
            }
        }
    }

    static public void sendTrackballEvent(MotionEvent event, int id, int displayId)
    {
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                mouseUp(id, displayId, (int) event.getX(), (int) event.getY());
                break;

            case MotionEvent.ACTION_DOWN:
                mouseDown(id, displayId, (int) event.getX(), (int) event.getY());
                m_oldx = (int) event.getX();
                m_oldy = (int) event.getY();
                break;

            case MotionEvent.ACTION_MOVE:
                int dx = (int) (event.getX() - m_oldx);
                int dy = (int) (event.getY() - m_oldy);
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        mouseMove(id, displayId, (int) event.getX(), (int) event.getY());
                        m_oldx = (int) event.getX();
                        m_oldy = (int) event.getY();
                }
                break;
        }
    }

    public static int checkSelfPermission(final String permission)
    {
        int perm = PackageManager.PERMISSION_DENIED;
        synchronized (m_mainActivityMutex) {
            if (m_activity == null)
                return perm;
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    if (m_checkSelfPermissionMethod == null)
                        m_checkSelfPermissionMethod = Context.class.getMethod("checkSelfPermission", String.class);
                    perm = (Integer)m_checkSelfPermissionMethod.invoke(m_activity, permission);
                } else {
                    final PackageManager pm = m_activity.getPackageManager();
                    perm = pm.checkPermission(permission, m_activity.getPackageName());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return perm;
    }

    private static void updateSelection(final int selStart,
                                        final int selEnd,
                                        final int candidatesStart,
                                        final int candidatesEnd)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.updateSelection(selStart, selEnd, candidatesStart, candidatesEnd);
            }
        });
    }

    private static void showSoftwareKeyboard(final int x,
                                             final int y,
                                             final int width,
                                             final int height,
                                             final int inputHints,
                                             final int enterKeyType)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.showSoftwareKeyboard(x, y, width, height, inputHints, enterKeyType);
            }
        });
    }

    private static void resetSoftwareKeyboard()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.resetSoftwareKeyboard();
            }
        });
    }

    private static void hideSoftwareKeyboard()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.hideSoftwareKeyboard();
            }
        });
    }

    private static void setFullScreen(final boolean fullScreen)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null) {
                    m_activityDelegate.setFullScreen(fullScreen);
                }
                updateWindow();
            }
        });
    }

    private static void registerClipboardManager()
    {
        if (m_service == null || m_activity != null) { // Avoid freezing if only service
            final Semaphore semaphore = new Semaphore(0);
            runAction(new Runnable() {
                @Override
                public void run() {
                    if (m_activity != null)
                        m_clipboardManager = (android.text.ClipboardManager) m_activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    semaphore.release();
                }
            });
            try {
                semaphore.acquire();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void setClipboardText(String text)
    {
        if (m_clipboardManager != null)
            m_clipboardManager.setText(text);
    }

    private static boolean hasClipboardText()
    {
        if (m_clipboardManager != null)
            return m_clipboardManager.hasText();
        else
            return false;
    }

    private static String getClipboardText()
    {
        if (m_clipboardManager != null)
            return m_clipboardManager.getText().toString();
        else
            return "";
    }

    private static void openContextMenu(final int x, final int y, final int w, final int h)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.openContextMenu(x, y, w, h);
            }
        });
    }

    private static void closeContextMenu()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.closeContextMenu();
            }
        });
    }

    private static void resetOptionsMenu()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.resetOptionsMenu();
            }
        });
    }

    private static void openOptionsMenu()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activity != null)
                    m_activity.openOptionsMenu();
            }
        });
    }

    private static byte[][] getSSLCertificates()
    {
        ArrayList<byte[]> certificateList = new ArrayList<byte[]>();

        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);

            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    X509TrustManager trustManager = (X509TrustManager) manager;

                    for (X509Certificate certificate : trustManager.getAcceptedIssuers()) {
                        byte buffer[] = certificate.getEncoded();
                        certificateList.add(buffer);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(QtTAG, "Failed to get certificates", e);
        }

        byte[][] certificateArray = new byte[certificateList.size()][];
        certificateArray = certificateList.toArray(certificateArray);
        return certificateArray;
    }

    private static void createSurface(final int id, final int displayId, final boolean onTop, final int x, final int y, final int w, final int h, final int imageDepth)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.createSurface(id, displayId, onTop, x, y, w, h, imageDepth);
            }
        });
    }

    private static void insertNativeView(final int id, final int displayId, final View view, final int x, final int y, final int w, final int h)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.insertNativeView(id, displayId, view, x, y, w, h);
            }
        });
    }

    private static void setSurfaceGeometry(final int id, final int displayId, final int x, final int y, final int w, final int h)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.setSurfaceGeometry(id, displayId, x, y, w, h);
            }
        });
    }

    private static void bringChildToFront(final int id, final int displayId)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.bringChildToFront(id, displayId);
            }
        });
    }

    private static void bringChildToBack(final int id, final int displayId)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.bringChildToBack(id, displayId);
            }
        });
    }

    private static void destroySurface(final int id, final int displayId)
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.destroySurface(id, displayId);
            }
        });
    }

    private static void initializeAccessibility()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                m_activityDelegate.initializeAccessibility();
            }
        });
    }

    private static void hideSplashScreen()
    {
        runAction(new Runnable() {
            @Override
            public void run() {
                if (m_activityDelegate != null)
                    m_activityDelegate.hideSplashScreen();
            }
        });
    }

    // screen methods
    public static native void setDisplayMetrics(int displayId,
                                                String name,
                                                int screenWidthPixels,
                                                int screenHeightPixels,
                                                int desktopWidthPixels,
                                                int desktopHeightPixels,
                                                double XDpi,
                                                double YDpi,
                                                double scaledDensity,
                                                double density);
    public static native void handleOrientationChanged(int newRotation, int nativeOrientation);
    public static native void removeDisplay(int displayId);
    // screen methods

    // pointer methods
    public static native void mouseDown(int winId, int displayId, int x, int y);
    public static native void mouseUp(int winId, int displayId, int x, int y);
    public static native void mouseMove(int winId, int displayId, int x, int y);
    public static native void touchBegin(int winId, int displayId);
    public static native void touchAdd(int winId, int displayId, int pointerId, int action, boolean primary, int x, int y, float size, float pressure);
    public static native void touchEnd(int winId, int displayId, int action);
    public static native void longPress(int winId, int displayId, int x, int y);
    // pointer methods

    // tablet methods
    public static native void tabletEvent(int winId, int deviceId, long time, int action, int pointerType, int buttonState, float x, float y, float pressure);
    // tablet methods

    // keyboard methods
    public static native void keyDown(int key, int unicode, int modifier, boolean autoRepeat);
    public static native void keyUp(int key, int unicode, int modifier, boolean autoRepeat);
    public static native void keyboardVisibilityChanged(boolean visibility);
    public static native void keyboardGeometryChanged(int x, int y, int width, int height);
    // keyboard methods

    // dispatch events methods
    public static native boolean dispatchGenericMotionEvent(MotionEvent ev);
    public static native boolean dispatchKeyEvent(KeyEvent event);
    // dispatch events methods

    // surface methods
    public static native void setSurface(int id, Object surface, int w, int h);
    // surface methods

    // window methods
    public static native void updateWindow();
    // window methods

    // application methods
    public static native void updateApplicationState(int state);

    // menu methods
    public static native boolean onPrepareOptionsMenu(Menu menu);
    public static native boolean onOptionsItemSelected(int itemId, boolean checked);
    public static native void onOptionsMenuClosed(Menu menu);

    public static native void onCreateContextMenu(ContextMenu menu);
    public static native void fillContextMenu(Menu menu);
    public static native boolean onContextItemSelected(int itemId, boolean checked);
    public static native void onContextMenuClosed(Menu menu);
    // menu methods

    // activity methods
    public static native void onActivityResult(int requestCode, int resultCode, Intent data);
    public static native void onNewIntent(Intent data);

    public static native void runPendingCppRunnables();

    private static native void setNativeActivity(Activity activity);
    private static native void setNativeService(Service service);
}

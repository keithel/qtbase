/****************************************************************************
**
** Copyright (C) 2012 BogDan Vatra <bogdan@kde.org>
** Contact: https://www.qt.io/licensing/
**
** This file is part of the plugins of the Qt Toolkit.
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

#ifndef QANDROIDPLATFORMINTERATION_H
#define QANDROIDPLATFORMINTERATION_H

#include <qpa/qplatformintegration.h>
#include <qpa/qplatformmenu.h>
#include <qpa/qplatformnativeinterface.h>

#include <EGL/egl.h>
#include <jni.h>
#include "qandroidinputcontext.h"

#include "qandroidplatformscreen.h"

#include <memory>

QT_BEGIN_NAMESPACE

typedef QHash<int, QAndroidPlatformScreen *> QAndroidPlatformScreensType;

class QDesktopWidget;
class QAndroidPlatformServices;
class QAndroidSystemLocale;
class QPlatformAccessibility;

struct AndroidStyle;
class QAndroidPlatformNativeInterface: public QPlatformNativeInterface
{
public:
    void *nativeResourceForIntegration(const QByteArray &resource);
    std::shared_ptr<AndroidStyle> m_androidStyle;
};

class QAndroidPlatformIntegration : public QPlatformIntegration
{
    friend class QAndroidPlatformScreen;

public:
    QAndroidPlatformIntegration(const QStringList &paramList);
    ~QAndroidPlatformIntegration();

    bool hasCapability(QPlatformIntegration::Capability cap) const;

    QPlatformWindow *createPlatformWindow(QWindow *window) const;
    QPlatformBackingStore *createPlatformBackingStore(QWindow *window) const;
    QPlatformOpenGLContext *createPlatformOpenGLContext(QOpenGLContext *context) const;
    QAbstractEventDispatcher *createEventDispatcher() const;
    QAndroidPlatformScreen *screen(int displayId = 0) { return m_screens.value(displayId); }
    const QAndroidPlatformScreensType *screens() { return &m_screens; }
    QPlatformOffscreenSurface *createPlatformOffscreenSurface(QOffscreenSurface *surface) const;

    virtual void setDesktopSize(int displayId, int width, int height);
    virtual void setDisplayMetrics(int displayId, int width, int height,
                                   qreal scaledDensity, qreal density);
    void setScreenSize(int displayId, int width, int height);
    bool isVirtualDesktop() { return true; }

    QPlatformFontDatabase *fontDatabase() const;

#ifndef QT_NO_CLIPBOARD
    QPlatformClipboard *clipboard() const;
#endif

    QPlatformInputContext *inputContext() const;
    QPlatformNativeInterface *nativeInterface() const;
    QPlatformServices *services() const;

#ifndef QT_NO_ACCESSIBILITY
    virtual QPlatformAccessibility *accessibility() const;
#endif

    QVariant styleHint(StyleHint hint) const;
    Qt::WindowState defaultWindowState(Qt::WindowFlags flags) const;

    QStringList themeNames() const;
    QPlatformTheme *createPlatformTheme(const QString &name) const;

    static void setDefaultDisplayMetrics(int displayId, int gw, int gh, int sw, int sh, int width, int height,
                                         qreal scaledDensity, qreal density);
    static void setScreenOrientation(Qt::ScreenOrientation currentOrientation,
                                     Qt::ScreenOrientation nativeOrientation);

    static qreal defaultDisplayPixelDensity();

    QTouchDevice *touchDevice() const { return m_touchDevice; }
    void setTouchDevice(QTouchDevice *touchDevice) { m_touchDevice = touchDevice; }
    static void setDefaultApplicationState(Qt::ApplicationState applicationState) { m_defaultApplicationState = applicationState; }

private:
    void screenAdded(QAndroidPlatformScreen *screen, bool isPrimary = false);

    EGLDisplay m_eglDisplay;
    QTouchDevice *m_touchDevice;

    QAndroidPlatformScreensType m_screens;

    QThread *m_mainThread;

    static QAndroidPlatformScreen *m_defaultScreen;

    static Qt::ScreenOrientation m_orientation;
    static Qt::ScreenOrientation m_nativeOrientation;

    static Qt::ApplicationState m_defaultApplicationState;

    QPlatformFontDatabase *m_androidFDB;
    QAndroidPlatformNativeInterface *m_androidPlatformNativeInterface;
    QAndroidPlatformServices *m_androidPlatformServices;

#ifndef QT_NO_CLIPBOARD
    QPlatformClipboard *m_androidPlatformClipboard;
#endif

    QAndroidSystemLocale *m_androidSystemLocale;
#ifndef QT_NO_ACCESSIBILITY
    mutable QPlatformAccessibility *m_accessibility;
#endif

    mutable QAndroidInputContext m_platformInputContext;
};

QT_END_NAMESPACE

#endif

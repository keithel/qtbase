/****************************************************************************
**
** Copyright (C) 2014 BogDan Vatra <bogdan@kde.org>
** Copyright (C) 2016 The Qt Company Ltd.
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

#include <QDebug>
#include <QTime>

#include <qpa/qwindowsysteminterface.h>

#include "qandroidplatformscreen.h"
#include "qandroidplatformbackingstore.h"
#include "qandroidplatformintegration.h"
#include "qandroidplatformwindow.h"
#include "androidjnimain.h"
#include "androidjnimenu.h"
#include "androiddeadlockprotector.h"

#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <qguiapplication.h>

#include <QtGui/QGuiApplication>
#include <QtGui/QWindow>
#include <QtGui/private/qwindow_p.h>

QT_BEGIN_NAMESPACE

#ifdef QANDROIDPLATFORMSCREEN_DEBUG
class ScopedProfiler
{
public:
    ScopedProfiler(const QString &msg)
    {
        m_msg = msg;
        m_timer.start();
    }
    ~ScopedProfiler()
    {
        qDebug() << m_msg << m_timer.elapsed();
    }

private:
    QTime m_timer;
    QString m_msg;
};

# define PROFILE_SCOPE ScopedProfiler ___sp___(__func__)
#else
# define PROFILE_SCOPE
#endif

QAndroidPlatformScreen::QAndroidPlatformScreen(int displayId, const QString& name, const QSize& physicalSize,
                                               qreal scaledDensity, qreal density, const QSize& size,
                                               const QRect& availableGeometry)
    : QObject(), QPlatformScreen()
    , m_availableGeometry(availableGeometry), m_depth(0), m_physicalSize(physicalSize)
    , m_scaledDensity(scaledDensity), m_density(density), m_displayId(displayId)
    , m_name(name), m_size(size)
{
    init();
}

QAndroidPlatformScreen::~QAndroidPlatformScreen()
{
    if (m_surfaceId != -1) {
        QtAndroid::destroySurface(m_surfaceId, m_displayId);
        m_surfaceWaitCondition.wakeOne();
        releaseSurface();
    }
}

void QAndroidPlatformScreen::init()
{
    // Initialize depth and format if not done so in constructor
    if (m_depth == 0)
    {
        // Raster only apps should set QT_ANDROID_RASTER_IMAGE_DEPTH to 16
        // is way much faster than 32
        if (qEnvironmentVariableIntValue("QT_ANDROID_RASTER_IMAGE_DEPTH") == 16) {
            m_format = QImage::Format_RGB16;
            m_depth = 16;
        } else {
            m_format = QImage::Format_ARGB32_Premultiplied;
            m_depth = 32;
        }
    }

    m_redrawTimer.setSingleShot(true);
    m_redrawTimer.setInterval(0);
}

void QAndroidPlatformScreen::connectScreen()
{
    connect(&m_redrawTimer, SIGNAL(timeout()), this, SLOT(doRedraw()));
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this, &QAndroidPlatformScreen::applicationStateChanged);
    m_connected = true;
}

QWindow *QAndroidPlatformScreen::topWindow() const
{
    Q_ASSERT(m_connected);
    for (QAndroidPlatformWindow *w : m_windowStack) {
        if (w->window()->type() == Qt::Window ||
                w->window()->type() == Qt::Popup ||
                w->window()->type() == Qt::Dialog) {
            return w->window();
        }
    }
    return 0;
}

QWindow *QAndroidPlatformScreen::topLevelAt(const QPoint &p) const
{
    Q_ASSERT(m_connected);
    for (QAndroidPlatformWindow *w : m_windowStack) {
        if (w->geometry().contains(p, false) && w->window()->isVisible())
            return w->window();
    }
    return 0;
}

void QAndroidPlatformScreen::addWindow(QAndroidPlatformWindow *window)
{
    Q_ASSERT(m_connected);
    if (window->parent() && window->isRaster())
        return;

    Q_ASSERT(!m_windowStack.contains(window));
    m_windowStack.prepend(window);
    if (window->isRaster()) {
        m_rasterSurfaces.ref();
        setDirty(window->geometry());
    }

    QWindow *w = topWindow();
    QWindowSystemInterface::handleWindowActivated(w);
    topWindowChanged(w);
}

void QAndroidPlatformScreen::removeWindow(QAndroidPlatformWindow *window)
{
    Q_ASSERT(m_connected);
    if (window->parent() && window->isRaster())
        return;


    Q_ASSERT(m_windowStack.contains(window));
    m_windowStack.removeOne(window);
    Q_ASSERT(!m_windowStack.contains(window));

    if (window->isRaster()) {
        m_rasterSurfaces.deref();
        setDirty(window->geometry());
    }

    QWindow *w = topWindow();
    QWindowSystemInterface::handleWindowActivated(w);
    topWindowChanged(w);
}

void QAndroidPlatformScreen::raise(QAndroidPlatformWindow *window)
{
    Q_ASSERT(m_connected);
    if (window->parent() && window->isRaster())
        return;

    int index = m_windowStack.indexOf(window);
    if (index <= 0)
        return;
    m_windowStack.move(index, 0);
    if (window->isRaster()) {
        setDirty(window->geometry());
    }
    QWindow *w = topWindow();
    QWindowSystemInterface::handleWindowActivated(w);
    topWindowChanged(w);
}

void QAndroidPlatformScreen::lower(QAndroidPlatformWindow *window)
{
    Q_ASSERT(m_connected);
    if (window->parent() && window->isRaster())
        return;

    int index = m_windowStack.indexOf(window);
    if (index == -1 || index == (m_windowStack.size() - 1))
        return;
    m_windowStack.move(index, m_windowStack.size() - 1);
    if (window->isRaster()) {
        setDirty(window->geometry());
    }
    QWindow *w = topWindow();
    QWindowSystemInterface::handleWindowActivated(w);
    topWindowChanged(w);
}

void QAndroidPlatformScreen::scheduleUpdate()
{
    Q_ASSERT(m_connected);
    if (!m_redrawTimer.isActive())
        m_redrawTimer.start();
}

void QAndroidPlatformScreen::setDirty(const QRect &rect)
{
    Q_ASSERT(m_connected);
    QRect intersection = rect.intersected(m_availableGeometry);
    m_dirtyRect |= intersection;
    scheduleUpdate();
}

void QAndroidPlatformScreen::setPhysicalSize(const QSize &size, double scaledDensity, double density)
{
    m_physicalSize = size;
    m_scaledDensity = scaledDensity;
    m_density = density;
}

void QAndroidPlatformScreen::setSize(const QSize &size)
{
    m_size = size;
    QWindowSystemInterface::handleScreenGeometryChange(QPlatformScreen::screen(), geometry(), availableGeometry());
}

void QAndroidPlatformScreen::setAvailableGeometry(const QRect &rect)
{
    QMutexLocker lock(&m_surfaceMutex);
    if (m_availableGeometry == rect)
        return;

    QRect oldGeometry = m_availableGeometry;

    m_availableGeometry = rect;

    if (m_connected)
    {
        QWindowSystemInterface::handleScreenGeometryChange(QPlatformScreen::screen(), geometry(), availableGeometry());
        resizeMaximizedWindows();

        if (oldGeometry.width() == 0 && oldGeometry.height() == 0 && rect.width() > 0 && rect.height() > 0) {
            QList<QWindow *> windows = QGuiApplication::allWindows();
            for (int i = 0; i < windows.size(); ++i) {
                QWindow *w = windows.at(i);
                if (w->handle()) {
                    QRect geometry = w->handle()->geometry();
                    if (geometry.width() > 0 && geometry.height() > 0)
                        QWindowSystemInterface::handleExposeEvent(w, QRect(QPoint(0, 0), geometry.size()));
                }
            }
        }

        if (m_surfaceId != -1) {
            releaseSurface();
            QtAndroid::setSurfaceGeometry(m_surfaceId, rect);
        }
    }
}

void QAndroidPlatformScreen::applicationStateChanged(Qt::ApplicationState state)
{
    Q_ASSERT(m_connected);
    for (QAndroidPlatformWindow *w : qAsConst(m_windowStack))
        w->applicationStateChanged(state);

    if (state <=  Qt::ApplicationHidden) {
        lockSurface();
        QtAndroid::destroySurface(m_surfaceId, m_displayId);
        m_surfaceId = -1;
        releaseSurface();
        unlockSurface();
    }
}

void QAndroidPlatformScreen::topWindowChanged(QWindow *w)
{
    Q_ASSERT(m_connected);
    QtAndroidMenu::setActiveTopLevelWindow(w);

    if (w != 0) {
        QAndroidPlatformWindow *platformWindow = static_cast<QAndroidPlatformWindow *>(w->handle());
        if (platformWindow != 0)
            platformWindow->updateStatusBarVisibility();
    }
}

int QAndroidPlatformScreen::rasterSurfaces()
{
    return m_rasterSurfaces;
}

void QAndroidPlatformScreen::doRedraw()
{
    Q_ASSERT(m_connected);
    PROFILE_SCOPE;
    if (!QtAndroid::activity())
        return;

    if (m_dirtyRect.isEmpty())
        return;

    // Stop if there are no visible raster windows. If we only have RasterGLSurface
    // windows that have renderToTexture children (i.e. they need the OpenGL path) then
    // we do not need an overlay surface.
    bool hasVisibleRasterWindows = false;
    for (QAndroidPlatformWindow *window : qAsConst(m_windowStack)) {
        if (window->window()->isVisible() && window->isRaster() && !qt_window_private(window->window())->compositing) {
            hasVisibleRasterWindows = true;
            break;
        }
    }
    if (!hasVisibleRasterWindows) {
        if (m_surfaceId != -1) {
            QtAndroid::destroySurface(m_surfaceId, m_displayId);
            m_surfaceId = -1;
        }
        return;
    }
    QMutexLocker lock(&m_surfaceMutex);
    if (m_surfaceId == -1 && m_rasterSurfaces) {
        m_surfaceId = QtAndroid::createSurface(this, m_displayId, m_availableGeometry, true, m_depth);
        AndroidDeadlockProtector protector;
        if (!protector.acquire())
            return;
        m_surfaceWaitCondition.wait(&m_surfaceMutex);
    }

    if (!m_nativeSurface)
        return;

    ANativeWindow_Buffer nativeWindowBuffer;
    ARect nativeWindowRect;
    nativeWindowRect.top = m_dirtyRect.top();
    nativeWindowRect.left = m_dirtyRect.left();
    nativeWindowRect.bottom = m_dirtyRect.bottom() + 1; // for some reason that I don't understand the QRect bottom needs to +1 to be the same with ARect bottom
    nativeWindowRect.right = m_dirtyRect.right() + 1; // same for the right

    int ret;
    if ((ret = ANativeWindow_lock(m_nativeSurface, &nativeWindowBuffer, &nativeWindowRect)) < 0) {
        qWarning() << "ANativeWindow_lock() failed! error=" << ret;
        return;
    }

    int bpp = 4;
    QImage::Format format = QImage::Format_RGBA8888_Premultiplied;
    if (nativeWindowBuffer.format == WINDOW_FORMAT_RGB_565) {
        bpp = 2;
        format = QImage::Format_RGB16;
    }

    QImage screenImage(reinterpret_cast<uchar *>(nativeWindowBuffer.bits)
                       , nativeWindowBuffer.width, nativeWindowBuffer.height
                       , nativeWindowBuffer.stride * bpp , format);

    QPainter compositePainter(&screenImage);
    compositePainter.setCompositionMode(QPainter::CompositionMode_Source);

    QRegion visibleRegion(m_dirtyRect);
    for (QAndroidPlatformWindow *window : qAsConst(m_windowStack)) {
        if (!window->window()->isVisible()
                || qt_window_private(window->window())->compositing
                || !window->isRaster())
            continue;

        const QVector<QRect> visibleRects = visibleRegion.rects();
        for (const QRect &rect : visibleRects) {
            QRect targetRect = window->geometry();
            targetRect &= rect;

            if (targetRect.isNull())
                continue;

            visibleRegion -= targetRect;
            QRect windowRect = targetRect.translated(-window->geometry().topLeft());
            QAndroidPlatformBackingStore *backingStore = static_cast<QAndroidPlatformWindow *>(window)->backingStore();
            if (backingStore)
                compositePainter.drawImage(targetRect.topLeft(), backingStore->toImage(), windowRect);
        }
    }

    foreach (const QRect &rect, visibleRegion.rects()) {
        compositePainter.fillRect(rect, QColor(Qt::transparent));
    }

    ret = ANativeWindow_unlockAndPost(m_nativeSurface);
    if (ret >= 0)
        m_dirtyRect = QRect();
}

QDpi QAndroidPlatformScreen::logicalDpi() const
{
    qreal lDpi = m_scaledDensity * 72;
    return QDpi(lDpi, lDpi);
}

Qt::ScreenOrientation QAndroidPlatformScreen::orientation() const
{
    return QAndroidPlatformIntegration::m_orientation;
}

Qt::ScreenOrientation QAndroidPlatformScreen::nativeOrientation() const
{
    return QAndroidPlatformIntegration::m_nativeOrientation;
}

void QAndroidPlatformScreen::surfaceChanged(JNIEnv *env, jobject surface, int w, int h)
{
    Q_ASSERT(m_connected);
    lockSurface();
    if (surface && w > 0  && h > 0) {
        releaseSurface();
        m_nativeSurface = ANativeWindow_fromSurface(env, surface);
        QMetaObject::invokeMethod(this, "setDirty", Qt::QueuedConnection, Q_ARG(QRect, QRect(0, 0, w, h)));
    } else {
        releaseSurface();
    }
    unlockSurface();
    m_surfaceWaitCondition.wakeOne();
}

void QAndroidPlatformScreen::releaseSurface()
{
    Q_ASSERT(m_connected);
    if (m_nativeSurface) {
        ANativeWindow_release(m_nativeSurface);
        m_nativeSurface = 0;
    }
}

QT_END_NAMESPACE

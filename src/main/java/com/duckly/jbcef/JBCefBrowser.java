// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.duckly.jbcef;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.LightEditActionFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.LazyInitializer;
import com.intellij.util.ui.UIUtil;
import com.duckly.jbcef.JCefAppConfig;
import com.duckly.jbcef.JCefVersionDetails;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.*;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.duckly.jbcef.JBCefEventUtils.*;
import static org.cef.callback.CefMenuModel.MenuId.MENU_ID_USER_LAST;

/**
 * A wrapper over {@link CefBrowser}.
 * <p>
 * Use {@link #getComponent()} as the browser's UI component.
 * Use {@link #loadURL(String)} or {@link #loadHTML(String)} for loading.
 *
 * @author tav
 */
public class JBCefBrowser implements JBCefDisposable {
  private static final String BLANK_URI = "about:blank";
  /**
   * According to
   * <a href="https://github.com/chromium/chromium/blob/55f44515cd0b9e7739b434d1c62f4b7e321cd530/third_party/blink/public/web/web_view.h#L191">SetZoomLevel</a>
   * docs, there is a geometric progression that starts with 0.0 and 1.2 common ratio.
   * Following functions provide API familiar to developers:
   * @see #setZoomLevel(double)
   * @see #getZoomLevel()
   */
  private static final double ZOOM_COMMON_RATIO = 1.2;
  private static final double LOG_ZOOM = Math.log(ZOOM_COMMON_RATIO);

  @SuppressWarnings("SpellCheckingInspection")
  private static final String JBCEFBROWSER_INSTANCE_PROP = "JBCefBrowser.instance";

  @NotNull private static final List<Consumer<? super JBCefBrowser>> ourOnBrowserMoveResizeCallbacks =
    Collections.synchronizedList(new ArrayList<>(1));

  @NotNull private final JBCefClient myCefClient;
  @NotNull private final JPanel myComponent;
  @NotNull private final CefBrowser myCefBrowser;
  @Nullable private volatile JBCefCookieManager myJBCefCookieManager;
  @NotNull private final CefFocusHandler myCefFocusHandler;
  @Nullable private final CefLifeSpanHandler myLifeSpanHandler;
  @NotNull private final CefKeyboardHandler myKeyboardHandler;
  @Nullable private final CefLoadHandler myLoadHandler;
  @NotNull private final DisposeHelper myDisposeHelper = new DisposeHelper();

  private final boolean myIsDefaultClient;
  private volatile boolean myIsCefBrowserCreated;
  @Nullable private volatile LoadDeferrer myLoadDeferrer;
  private JDialog myDevtoolsFrame = null;
  protected CefContextMenuHandler myDefaultContextMenuHandler;
  private final ReentrantLock myCookieManagerLock = new ReentrantLock();

  private static final LazyInitializer.NotNullValue<String> ERROR_PAGE_READER =
    new LazyInitializer.NotNullValue<String>() {
      @Override
      public @NotNull String initialize() {
        try {
          return new String(FileUtil.loadBytes(Objects.requireNonNull(
              JBCefApp.class.getResourceAsStream("resources/load_error.html"))), StandardCharsets.UTF_8);
        }
        catch (IOException | NullPointerException e) {
          Logger.getInstance(JBCefBrowser.class).error("couldn't find load_error.html", e);
        }
        return "";
      }
    };

  private static final class LoadDeferrer {
    @Nullable private final String myHtml;
    @NotNull private final String myUrl;

    private LoadDeferrer(@Nullable String html, @NotNull String url) {
      myHtml = html;
      myUrl = url;
    }

    @NotNull
    public static LoadDeferrer urlDeferrer(String url) {
      return new LoadDeferrer(null, url);
    }

    @NotNull
    public static LoadDeferrer htmlDeferrer(String html, String url) {
      return new LoadDeferrer(html, url);
    }

    public void load(@NotNull CefBrowser browser) {
      // JCEF demands async loading.
      SwingUtilities.invokeLater(
        myHtml == null ?
          () -> browser.loadURL(myUrl) :
          () -> loadString(browser, myHtml, myUrl));
    }
  }

  private static final class ShortcutProvider {
    // Since these CefFrame::* methods are available only with JCEF API 1.1 and higher, we are adding no shortcuts for older JCEF
    private static final List<Pair<String, AnAction>> ourActions = isSupportedByJCefApi() ? Arrays.asList(
      createAction("$Cut", CefFrame::cut),
      createAction("$Copy", CefFrame::copy),
      createAction("$Paste", CefFrame::paste),
//      createAction("$Delete", CefFrame::delete),
//      createAction("$SelectAll", CefFrame::selectAll),
      createAction("$Undo", CefFrame::undo),
      createAction("$Redo", CefFrame::redo)
    ) : Collections.emptyList();

    // This method may be deleted when JCEF API version check is included into JBCefApp#isSupported
    private static boolean isSupportedByJCefApi() {
      try {
        /* getVersionDetails() was introduced alongside JCEF API versioning with first version of 1.1, which also added these necessary
         * for shortcuts to work CefFrame methods. Therefore successful call to getVersionDetails() means our JCEF API is at least 1.1 */
        JCefAppConfig.getVersionDetails();
        return true;
      }
      catch (NoSuchMethodError | JCefVersionDetails.VersionUnavailableException e) {
        Logger.getInstance(ShortcutProvider.class).warn("JCEF shortcuts are unavailable (incompatible API)", e);
        return false;
      }
    }

    private static Pair<String, AnAction> createAction(String shortcut, Consumer<CefFrame> action) {
      return Pair.create(
        shortcut,
        LightEditActionFactory.create(event -> {
          Component component = event.getData(PlatformDataKeys.CONTEXT_COMPONENT);
          if (component == null) return;
          Component parentComponent = component.getParent();
          if (!(parentComponent instanceof JComponent)) return;
          Object browser = ((JComponent)parentComponent).getClientProperty(JBCEFBROWSER_INSTANCE_PROP);
          if (!(browser instanceof JBCefBrowser)) return;
          action.accept(((JBCefBrowser) browser).getCefBrowser().getFocusedFrame());
        })
      );
    }

    private static void registerShortcuts(JComponent uiComp, JBCefBrowser jbCefBrowser) {
      ActionManager actionManager = ActionManager.getInstance();
      for (Pair<String, AnAction> action : ourActions) {
        action.second.registerCustomShortcutSet(actionManager.getAction(action.first).getShortcutSet(), uiComp, jbCefBrowser);
      }
    }
  }

  /**
   * Creates a browser with the provided {@code JBCefClient} and initial URL. The client's lifecycle is the responsibility of the caller.
   */
  public JBCefBrowser(@NotNull JBCefClient client, @Nullable String url) {
    this(client, false, url);
  }

  public JBCefBrowser(@NotNull CefBrowser cefBrowser, @NotNull JBCefClient client) {
    this(cefBrowser, client, false, null);
  }

  public JBCefBrowser(@NotNull JBCefClient client, boolean isDefaultClient, @Nullable String url) {
    this(null, client, isDefaultClient, url);
  }

  private JBCefBrowser(@Nullable CefBrowser cefBrowser, @NotNull JBCefClient client, boolean isDefaultClient, @Nullable String url) {
    if (client.isDisposed()) {
      throw new IllegalArgumentException("JBCefClient is disposed");
    }
    myCefClient = client;
    myIsDefaultClient = isDefaultClient;

    myComponent = SystemInfoRt.isWindows ?
      new JPanel(new BorderLayout()) {
        @Override
        public void removeNotify() {
          if (myCefBrowser.getUIComponent().hasFocus()) {
            // pass focus before removal
            myCefBrowser.setFocus(false);
          }
          super.removeNotify();
        }
      } :
      new JPanel(new BorderLayout());

    myComponent.setBackground(JBColor.background());

    myCefBrowser = cefBrowser != null ?
      cefBrowser : myCefClient.getCefClient().createBrowser(url != null ? url : BLANK_URI, JBCefApp.isOffScreenRenderingMode(), false);
    Component uiComp = myCefBrowser.getUIComponent();
    myComponent.putClientProperty(JBCEFBROWSER_INSTANCE_PROP, this);
    if (SystemInfoRt.isMac) {
      // We handle shortcuts manually on MacOS: https://www.magpcss.org/ceforum/viewtopic.php?f=6&t=12561
      ShortcutProvider.registerShortcuts(myComponent, this);
    }
    myComponent.add(uiComp, BorderLayout.CENTER);

    myComponent.setFocusCycleRoot(true);
    myComponent.setFocusTraversalPolicyProvider(true);
    myComponent.setFocusTraversalPolicy(new MyFTP());

    myComponent.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        ourOnBrowserMoveResizeCallbacks.forEach(callback -> callback.accept(JBCefBrowser.this));
      }

      @Override
      public void componentMoved(ComponentEvent e) {
        ourOnBrowserMoveResizeCallbacks.forEach(callback -> callback.accept(JBCefBrowser.this));
      }

      @Override
      public void componentShown(ComponentEvent e) {
        ourOnBrowserMoveResizeCallbacks.forEach(callback -> callback.accept(JBCefBrowser.this));
      }

      @Override
      public void componentHidden(ComponentEvent e) {
        ourOnBrowserMoveResizeCallbacks.forEach(callback -> callback.accept(JBCefBrowser.this));
      }
    });

    if (cefBrowser == null) {
      myCefClient.addLoadHandler(myLoadHandler = new CefLoadHandlerAdapter() {
        volatile String lastLoadUrl = "";
        @Override
        public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
          lastLoadUrl = frame.getURL();
        }
        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
          if (lastLoadUrl.equals(failedUrl)) {
            UIUtil.invokeLaterIfNeeded(() -> loadErrorPage(errorText, failedUrl));
          }
        }
      }, myCefBrowser);

      myCefClient.addLifeSpanHandler(myLifeSpanHandler = new CefLifeSpanHandlerAdapter() {
          @Override
          public void onAfterCreated(CefBrowser browser) {
            myIsCefBrowserCreated = true;
            LoadDeferrer loader = myLoadDeferrer;
            if (loader != null) {
              loader.load(browser);
              myLoadDeferrer = null;
            }
          }
        }, myCefBrowser);
    }
    else {
      myLifeSpanHandler = null;
      myLoadHandler = null;
    }

    myCefClient.addFocusHandler(myCefFocusHandler = new CefFocusHandlerAdapter() {
      @Override
      public boolean onSetFocus(CefBrowser browser, FocusSource source) {
        if (source == FocusSource.FOCUS_SOURCE_NAVIGATION) {
          if (SystemInfoRt.isWindows) {
            myCefBrowser.setFocus(false);
          }
          return true; // suppress focusing the browser on navigation events
        }
        if (SystemInfoRt.isLinux) {
          browser.getUIComponent().requestFocus();
        }
        else {
          browser.getUIComponent().requestFocusInWindow();
        }
        return false;
      }
    }, myCefBrowser);

    if (SystemInfoRt.isWindows) {
      myCefBrowser.getUIComponent().addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
          if (myCefBrowser.getUIComponent().isFocusable()) {
            myCefBrowser.setFocus(true);
          }
        }
      });
    }

    myCefClient.addKeyboardHandler(myKeyboardHandler = new CefKeyboardHandlerAdapter() {
      @Override
      public boolean onKeyEvent(CefBrowser browser, CefKeyEvent cefKeyEvent) {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean consume = focusOwner != browser.getUIComponent();
        if (consume && SystemInfoRt.isMac && isUpDownKeyEvent(cefKeyEvent)) return true; // consume

        Window focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (focusedWindow == null) {
          return true; // consume
        }
        KeyEvent javaKeyEvent = convertCefKeyEvent(cefKeyEvent, focusedWindow);
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(javaKeyEvent);
        return consume;
      }
    }, myCefBrowser);

    myDefaultContextMenuHandler = createDefaultContextMenuHandler();
    myCefClient.addContextMenuHandler(myDefaultContextMenuHandler, this.getCefBrowser());
  }

  protected DefaultCefContextMenuHandler createDefaultContextMenuHandler() {
    boolean isInternal = ApplicationManager.getApplication().isInternal();
    return new DefaultCefContextMenuHandler(isInternal);
  }

  /**
   * Loads URL.
   */
  public void loadURL(@NotNull String url) {
    if (myIsCefBrowserCreated) {
      myCefBrowser.loadURL(url);
    }
    else {
      myLoadDeferrer = LoadDeferrer.urlDeferrer(url);
    }
  }

  /**
   * Loads html content.
   *
   * @param html content to load
   * @param url a dummy URL that may affect restriction policy applied to the content
   */
  public void loadHTML(@NotNull String html, @NotNull String url) {
    if (myIsCefBrowserCreated) {
      loadString(myCefBrowser, html, url);
    }
    else {
      myLoadDeferrer = LoadDeferrer.htmlDeferrer(html, url);
    }
  }

  /**
   * Loads html content.
   */
  public void loadHTML(@NotNull String html) {
    loadHTML(html, BLANK_URI);
  }

  private static void loadString(CefBrowser cefBrowser, String html, String url) {
    url = JBCefFileSchemeHandlerFactory.registerLoadHTMLRequest(cefBrowser, html, url);
    cefBrowser.loadURL(url);
  }

//  /**
//   * Creates a browser with default {@link JBCefClient}. The default client is disposed with this browser and may not be used with other browsers.
//   */
//  @SuppressWarnings("unused")
//  public JBCefBrowser() {
//    this(JBCefApp.getInstance().createClient(), true, null);
//  }
//
//  /**
//   * @see #JBCefBrowser()
//   * @param url initial url
//   */
//  @SuppressWarnings("unused")
//  public JBCefBrowser(@NotNull String url) {
//    this(JBCefApp.getInstance().createClient(), true, url);
//  }

  @NotNull
  public JComponent getComponent() {
    return myComponent;
  }

  @NotNull
  public CefBrowser getCefBrowser() {
    return myCefBrowser;
  }

  /**
   * @param zoomLevel 1.0 is 100%.
   * @see #ZOOM_COMMON_RATIO
   */
  public void setZoomLevel(double zoomLevel) {
    myCefBrowser.setZoomLevel(Math.log(zoomLevel) / LOG_ZOOM);
  }

  /**
   * @return 1.0 is 100%
   * @see #ZOOM_COMMON_RATIO
   */
  public double getZoomLevel() {
    return Math.pow(ZOOM_COMMON_RATIO, myCefBrowser.getZoomLevel());
  }

  @NotNull
  public JBCefClient getJBCefClient() {
    return myCefClient;
  }

  @NotNull
  public JBCefCookieManager getJBCefCookieManager() {
    myCookieManagerLock.lock();
    try {
      if (myJBCefCookieManager == null) {
        myJBCefCookieManager = new JBCefCookieManager();
      }
      return Objects.requireNonNull(myJBCefCookieManager);
    }
    finally {
      myCookieManagerLock.unlock();
    }
  }

  @SuppressWarnings("unused")
  public void setJBCefCookieManager(@NotNull JBCefCookieManager jBCefCookieManager) {
    myCookieManagerLock.lock();
    try {
      myJBCefCookieManager = jBCefCookieManager;
    }
    finally {
      myCookieManagerLock.unlock();
    }
  }

  @Nullable
  private static Window getActiveFrame() {
    for (Frame frame : Frame.getFrames()) {
      if (frame.isActive()) return frame;
    }
    return null;
  }

  public void openDevtools() {
    if (myDevtoolsFrame != null) {
      myDevtoolsFrame.toFront();
      return;
    }

    Window activeFrame = getActiveFrame();
    if (activeFrame == null) return;
    Rectangle bounds = activeFrame.getGraphicsConfiguration().getBounds();

    myDevtoolsFrame = new JDialog(activeFrame);
    myDevtoolsFrame.setTitle("JCEF DevTools");
    myDevtoolsFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    myDevtoolsFrame.setBounds(bounds.width / 4 + 100, bounds.height / 4 + 100, bounds.width / 2, bounds.height / 2);
    myDevtoolsFrame.setLayout(new BorderLayout());
    JBCefBrowser devTools = new JBCefBrowser(myCefBrowser.getDevTools(), myCefClient);
    myDevtoolsFrame.add(devTools.getComponent(), BorderLayout.CENTER);
    myDevtoolsFrame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosed(WindowEvent e) {
        myDevtoolsFrame = null;
        Disposer.dispose(devTools);
      }
    });
    myDevtoolsFrame.setVisible(true);
  }

  @Override
  public void dispose() {
    myDisposeHelper.dispose(() -> {
      myCefClient.removeFocusHandler(myCefFocusHandler, myCefBrowser);
      myCefClient.removeKeyboardHandler(myKeyboardHandler, myCefBrowser);
      if (myLifeSpanHandler != null) myCefClient.removeLifeSpanHandler(myLifeSpanHandler, myCefBrowser);
      if (myLoadHandler != null) myCefClient.removeLoadHandler(myLoadHandler, myCefBrowser);
      myCefBrowser.stopLoad();
      myCefBrowser.close(true);
      if (myIsDefaultClient) {
        Disposer.dispose(myCefClient);
      }
    });
  }

  @Override
  public boolean isDisposed() {
    return myDisposeHelper.isDisposed();
  }

  boolean isCefBrowserCreated() {
    return myIsCefBrowserCreated;
  }

  /**
   * Returns {@code JBCefBrowser} instance associated with this {@code CefBrowser}.
   */
  @Nullable
  public static JBCefBrowser getJBCefBrowser(@NotNull CefBrowser browser) {
    Component uiComp = browser.getUIComponent();
    if (uiComp != null) {
      Component parentComp = uiComp.getParent();
      if (parentComp instanceof JComponent) {
        return (JBCefBrowser)((JComponent)parentComp).getClientProperty(JBCEFBROWSER_INSTANCE_PROP);
      }
    }
    return null;
  }

  /**
   * For internal usage.
   */
  public static void addOnBrowserMoveResizeCallback(@NotNull Consumer<? super JBCefBrowser> callback) {
    ourOnBrowserMoveResizeCallbacks.add(callback);
  }

  /**
   * For internal usage.
   */
  public static void removeOnBrowserMoveResizeCallback(@NotNull Consumer<? super JBCefBrowser> callback) {
    ourOnBrowserMoveResizeCallbacks.remove(callback);
  }

  private void loadErrorPage(@NotNull String errorText, @NotNull String failedUrl) {
    int fontSize = (int)(EditorColorsManager.getInstance().getGlobalScheme().getEditorFontSize() * 1.1);
    int headerFontSize = fontSize + JBUIScale.scale(3);
    int headerPaddingTop = headerFontSize / 5;
    int lineHeight = headerFontSize * 2;
    int iconPaddingRight = JBUIScale.scale(12);
    Color bgColor = JBColor.background();
    String bgWebColor = String.format("#%02x%02x%02x", bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
    Color fgColor = JBColor.foreground();
    String fgWebColor = String.format("#%02x%02x%02x", fgColor.getRed(), fgColor.getGreen(), fgColor.getBlue());

    String html = ERROR_PAGE_READER.get();
    html = html.replace("${lineHeight}", String.valueOf(lineHeight));
    html = html.replace("${iconPaddingRight}", String.valueOf(iconPaddingRight));
    html = html.replace("${fontSize}", String.valueOf(fontSize));
    html = html.replace("${headerFontSize}", String.valueOf(headerFontSize));
    html = html.replace("${headerPaddingTop}", String.valueOf(headerPaddingTop));
    html = html.replace("${bgWebColor}", bgWebColor);
    html = html.replace("${fgWebColor}", fgWebColor);
    html = html.replace("${errorText}", errorText);
    html = html.replace("${failedUrl}", failedUrl);

    loadHTML(html);
  }

  protected class DefaultCefContextMenuHandler extends CefContextMenuHandlerAdapter {
    protected static final int DEBUG_COMMAND_ID = MENU_ID_USER_LAST;
    private final boolean isInternal;

    public DefaultCefContextMenuHandler(boolean isInternal) {
      this.isInternal = isInternal;
    }

    @Override
    public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
      if (isInternal) {
        model.addItem(DEBUG_COMMAND_ID, "Open DevTools");
      }
    }

    @Override
    public boolean onContextMenuCommand(CefBrowser browser, CefFrame frame, CefContextMenuParams params, int commandId, int eventFlags) {
      if (commandId == DEBUG_COMMAND_ID) {
        openDevtools();
        return true;
      }
      return false;
    }
  }

  private class MyFTP extends FocusTraversalPolicy {
    @Override
    public Component getComponentAfter(Container aContainer, Component aComponent) {
      return myCefBrowser.getUIComponent();
    }

    @Override
    public Component getComponentBefore(Container aContainer, Component aComponent) {
      return myCefBrowser.getUIComponent();
    }

    @Override
    public Component getFirstComponent(Container aContainer) {
      return myCefBrowser.getUIComponent();
    }

    @Override
    public Component getLastComponent(Container aContainer) {
      return myCefBrowser.getUIComponent();
    }

    @Override
    public Component getDefaultComponent(Container aContainer) {
      return myCefBrowser.getUIComponent();
    }
  }
}
